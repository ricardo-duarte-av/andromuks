package net.vrkknn.andromuks.utils

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.MediaInfo
import net.vrkknn.andromuks.MediaMessage
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.CacheUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.MediaUtils
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.utils.extractRoomLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File
import java.net.URLDecoder
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

private val matrixUserRegex = Regex("matrix:(?:/+)?(?:u|user)/(@?.+)")

/**
 * How much larger emoji-only messages render than body text. Shared by every render path so a
 * message does not change size depending on whether it arrived with a formatted_body.
 */
private const val EMOJI_ONLY_FONT_SCALE = 2

// Styles for the plain-text (non-HTML) render path. Constant, so hoisted out of the composable:
// they were being reallocated on every recomposition, and as top-level vals they can also be
// captured by the memoized render without becoming a `remember` key.
private val LINK_STYLE = SpanStyle(color = Color(0xFF1A73E8), textDecoration = TextDecoration.Underline)
private val MONOSPACE_STYLE = SpanStyle(fontFamily = FontFamily.Monospace)

private class SpoilerRenderContext(private val states: SnapshotStateMap<String, Boolean>) {
    private var counter = 0
    private val usedIds = mutableSetOf<String>()

    fun start() {
        counter = 0
        usedIds.clear()
    }

    /**
     * IDs are positional and deterministic (`spoiler_0`, `spoiler_1`, …), so the same node tree
     * always yields the same IDs — which is what makes the render walk cacheable.
     *
     * Deliberately does **not** seed `states[id] = false`. That write was redundant: [isRevealed]
     * reads `states[id] == true` (absent → false) and [toggle] reads `states[id] ?: false`
     * (absent → true), so an absent key already behaves exactly like a stored `false`. It was
     * also the only snapshot *write* inside the render walk, which is what previously made the
     * walk impossible to wrap in `derivedStateOf` — writing snapshot state inside a derived
     * calculation invalidates the very state being computed.
     *
     * With it gone, `states` holds only spoilers the user has actually toggled.
     */
    fun nextId(): String {
        val id = "spoiler_$counter"
        counter++
        usedIds.add(id)
        return id
    }

    fun isRevealed(id: String): Boolean = states[id] == true

    fun toggle(id: String) {
        states[id] = !(states[id] ?: false)
    }

    fun cleanup() {
        val iterator = states.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (!usedIds.contains(key)) {
                iterator.remove()
            }
        }
    }
}

private fun maskSpoilerText(text: String): String {
    if (text.isEmpty()) return ""

    val builder = StringBuilder()
    var segmentLength = 0

    fun flushSegment() {
        if (segmentLength <= 0) return
        builder.append(maskSegment(segmentLength))
        segmentLength = 0
    }

    text.forEach { char ->
        if (char == '\n' || char == '\r') {
            flushSegment()
            builder.append(char)
        } else {
            segmentLength++
        }
    }

    flushSegment()
    return builder.toString()
}

private fun maskSegment(length: Int): String {
    if (length <= 0) return ""
    if (length == 1) return "*"
    if (length == 2) return "<>"
    if (length in 3..8) {
        return buildString {
            append('<')
            repeat(length - 2) { append('*') }
            append('>')
        }
    }

    val base = "spoiler"
    val baseLength = base.length + 2 // includes <>
    val extra = length - baseLength
    val leftExtra = (extra + 1) / 2
    val rightExtra = extra - leftExtra

    return buildString {
        append('<')
        repeat(leftExtra) { append('-') }
        append(base)
        repeat(rightExtra) { append('-') }
        append('>')
    }
}

/**
 * Allowed HTML tags according to Matrix spec for safe rendering
 */
private val ALLOWED_HTML_TAGS = setOf(
    "del", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "p", "a", "ul", "ol",
    "sup", "sub", "li", "b", "i", "u", "strong", "em", "s", "strike", "ins", "code", "hr", "br",
    "div", "table", "thead", "tbody", "tr", "th", "td", "caption", "pre", "span",
    "font", "img", "details", "summary",
    // MSC2191 maths: gomuks emits <hicli-math displaymode="inline|block" latex="...">
    "hicli-math",
)

/**
 * Represents a parsed HTML node
 */
sealed class HtmlNode {
    data class Text(val content: String) : HtmlNode()
    data class Tag(val name: String, val attributes: Map<String, String>, val children: List<HtmlNode>) : HtmlNode()
    data class LineBreak(val dummy: Unit = Unit) : HtmlNode()
}

/**
 * Simple HTML parser that handles the allowed Matrix HTML tags
 */
object HtmlParser {
    /**
     * Process-wide cache of parsed trees, keyed on the raw markup.
     *
     * `HtmlMessageText` already memoizes its parse with `remember(sanitizedHtml)`, but that is
     * scoped to one composition: scroll a message off the LazyColumn and back and the whole tree
     * is parsed again from scratch. Repeated scroll-back over the same messages was re-parsing
     * continuously.
     *
     * Safe to share: [HtmlNode] is a sealed hierarchy of data classes with `val` fields and
     * immutable collections, so no consumer can mutate an entry out from under another.
     *
     * Evicted wholesale by [clearParseCache] on real memory pressure (see AndromuksApplication).
     */
    private const val PARSE_CACHE_ENTRIES = 400
    private val parseCache = android.util.LruCache<String, List<HtmlNode>>(PARSE_CACHE_ENTRIES)

    /** [parse], memoized across compositions. Prefer this on render paths. */
    fun parseCached(html: String): List<HtmlNode> = parseCache.get(html) ?: parse(html).also { parseCache.put(html, it) }

    fun clearParseCache() {
        parseCache.evictAll()
    }

    /**
     * Parse sanitized HTML into a tree of [HtmlNode]s.
     *
     * jsoup does the tokenizing; this only adapts its DOM to [HtmlNode] and applies the
     * Matrix-specific rules the renderer relies on (allowlist, `mx-reply` stripping, mxc-only
     * images). The previous hand-rolled scanner failed the way regex HTML parsing always does —
     * most visibly, its attribute regex required `name=value`, so a valueless attribute such as
     * `<span data-mx-spoiler>` was dropped entirely and the spoiler rendered unmasked.
     */
    fun parse(html: String): List<HtmlNode> = try {
        Jsoup.parseBodyFragment(html).body().childNodes().flatMap { convert(it) }
            .trimTrailingWhitespaceOfLastText()
    } catch (e: Exception) {
        Log.e("Andromuks", "HtmlParser: Failed to parse HTML", e)
        emptyList()
    }

    /**
     * One jsoup node becomes zero, one, or several [HtmlNode]s — several when a disallowed tag is
     * unwrapped in favour of its children, zero when it is dropped outright.
     */
    private fun convert(node: Node): List<HtmlNode> {
        if (node is TextNode) {
            // wholeText, not text(): the latter normalises runs of whitespace, which would eat the
            // spaces between inline tags and flatten <pre>. Entities are already decoded by jsoup,
            // so decodeHtmlEntities must NOT be applied again here — "&amp;lt;" is meant to stay
            // the literal text "&lt;".
            val text = node.wholeText
            return if (text.isEmpty()) emptyList() else listOf(HtmlNode.Text(text))
        }
        if (node !is Element) {
            // Comments, doctypes, CDATA: nothing to render.
            return emptyList()
        }

        val tagName = node.normalName()

        // Matrix rich reply fallback: dropped whole, including its subtree.
        if (tagName == "mx-reply") return emptyList()

        if (!ALLOWED_HTML_TAGS.contains(tagName)) {
            Log.w("Andromuks", "HtmlParser: Unwrapping disallowed tag: $tagName")
            // Unwrap rather than drop, so the text inside an unknown wrapper still renders.
            return node.childNodes().flatMap { convert(it) }
        }

        val attributes = node.attributes().associate { it.key.lowercase() to it.value }

        return when (tagName) {
            "br" -> listOf(HtmlNode.LineBreak())
            "hr" -> listOf(HtmlNode.Tag("hr", emptyMap(), emptyList()))
            "img" -> listOf(convertImage(attributes))
            else -> listOf(HtmlNode.Tag(tagName, attributes, node.childNodes().flatMap { convert(it) }))
        }
    }

    /**
     * Images must come from Matrix media. A remote http(s) src would leak the reader's IP to a
     * third-party host, so it is refused and replaced by its alt text.
     */
    private fun convertImage(attributes: Map<String, String>): HtmlNode {
        val src = attributes["src"] ?: attributes["data-mxc"] ?: ""
        return if (src.startsWith("http://") || src.startsWith("https://")) {
            Log.w("Andromuks", "HtmlParser: Refusing to load image from HTTP(S) URL: $src")
            HtmlNode.Text(attributes["alt"] ?: attributes["title"] ?: "[Image]")
        } else {
            HtmlNode.Tag("img", attributes, emptyList())
        }
    }

    /**
     * Trailing whitespace on the very last text run is dropped, so markup that ends with a newline
     * does not render an empty final line. Interior whitespace is left alone — it is meaningful
     * between inline tags.
     */
    private fun List<HtmlNode>.trimTrailingWhitespaceOfLastText(): List<HtmlNode> {
        val last = lastOrNull()
        if (last !is HtmlNode.Text) return this
        val trimmed = last.content.trimEnd()
        return if (trimmed.isEmpty()) {
            dropLast(1)
        } else {
            dropLast(1) + HtmlNode.Text(trimmed)
        }
    }
}

/**
 * Drop leading and trailing `<br>` children from a node list.
 *
 * Only the edges: an interior break is a real line separator and must survive. gomuks emits
 * `<blockquote>text<br/></blockquote>` for a one-line markdown quote, and [appendBlockQuote]
 * answers every [HtmlNode.LineBreak] by opening a fresh prefixed line ("│ "). At the end of the
 * quote that line never receives any content, so the message rendered with a dangling "│" of its
 * own. It is not a trailing newline either, so the end-of-render trim in [HtmlMessageText] cannot
 * clean it up afterwards.
 */
internal fun List<HtmlNode>.trimEdgeLineBreaks(): List<HtmlNode> {
    var start = 0
    var end = size
    while (start < end && this[start] is HtmlNode.LineBreak) start++
    while (end > start && this[end - 1] is HtmlNode.LineBreak) end--
    return if (start == 0 && end == size) this else subList(start, end)
}

/**
 * Data class for inline images
 */
data class InlineImageData(
    val src: String,
    val alt: String,
    val height: Int,
    val isHidden: Boolean = false,
    // MSC2191 maths: when non-null, this entry is a LaTeX equation rendered via JLaTeXMath
    // instead of a network image. `alt` holds the raw LaTeX (used as the text fallback).
    val latex: String? = null,
    // The size attributes exactly as the markup declared them, null when absent. `height` above
    // substitutes a default so inline rendering always has a number; these keep the distinction
    // for [inlineImageSizeSp], which needs to know whether an aspect ratio was actually given.
    val declaredWidth: Int? = null,
    val declaredHeight: Int? = null,
)

/**
 * Opt-in sizing for inline `<img>` elements, used where the markup is being shown at full size
 * rather than as a chat line — the expanded profile-bio viewer, for instance. Without it, images
 * are clamped to the height of one line of text so they read as emoticons.
 *
 * Both bounds are in sp so they track the same font scaling the surrounding text does.
 */
data class InlineImageSizing(val maxHeightSp: Float, val maxWidthSp: Float)

/**
 * Size an inline image for [InlineImageSizing]: honour the declared width/height (and therefore
 * the declared aspect ratio), then shrink to fit inside both bounds. A declared size is never
 * enlarged — `height="32"` stays 32, the way the markup asked for.
 *
 * Markup that declares no size would, in a browser, render at the image's intrinsic size, which
 * isn't known until the bitmap loads while a [Placeholder] must be sized before that. Those fall
 * back to [fallbackHeightSp] — the ordinary inline size, one line of text — rather than to the cap:
 * gomuks' server-rendered profile bios drop the `height` attribute from some images, and sizing
 * those from the cap blew every emoticon in the bio up to the full width of the window.
 */
internal fun inlineImageSizeSp(data: InlineImageData, sizing: InlineImageSizing, fallbackHeightSp: Float): Pair<Float, Float> {
    val declaredWidth = data.declaredWidth?.takeIf { it > 0 }?.toFloat()
    val declaredHeight = data.declaredHeight?.takeIf { it > 0 }?.toFloat()
    val aspect = if (declaredWidth != null && declaredHeight != null) declaredWidth / declaredHeight else 1f
    var height = (declaredHeight ?: declaredWidth ?: fallbackHeightSp).coerceAtMost(sizing.maxHeightSp)
    var width = height * aspect
    if (width > sizing.maxWidthSp && width > 0f) {
        height *= sizing.maxWidthSp / width
        width = sizing.maxWidthSp
    }
    return width to height
}

data class InlineMatrixUserChip(val userId: String, val displayText: String, val avatarUrl: String? = null)

data class InlineMatrixRoomChip(
    val roomLink: RoomLink,
    val displayText: String,
    val isJoined: Boolean,
    val roomName: String? = null,
    val roomAvatarUrl: String? = null,
)

data class InlineCodeBlockPreview(val previewText: String, val fullCode: String, val totalLines: Int)

private fun extractMatrixUserId(href: String): String? {
    val trimmed = href.trim()
    if (trimmed.startsWith("https://matrix.to/#/")) {
        val encoded = trimmed.removePrefix("https://matrix.to/#/")
        val decoded = runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()
        return decoded?.takeIf { it.startsWith("@") }
    }
    if (trimmed.startsWith("matrix:")) {
        val match = matrixUserRegex.find(trimmed)
        val raw = match?.groupValues?.getOrNull(1) ?: return null
        // Handle both @user:server.com and user:server.com formats
        val userId = if (raw.startsWith("@")) {
            raw
        } else {
            "@$raw"
        }
        val decoded = runCatching {
            URLDecoder.decode(
                userId.removePrefix("@"),
                Charsets.UTF_8.name(),
            )
        }.getOrNull() ?: userId.removePrefix("@")
        return "@$decoded"
    }
    return null
}

private fun AnnotatedString.Builder.appendHtmlNode(
    node: HtmlNode,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext? = null,
    hideContent: Boolean = false,
    previousWasLineBreak: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    when (node) {
        is HtmlNode.Text -> {
            var text = node.content
            // Garble text if requested (for spoilers) - do this FIRST on original text to preserve exact length
            text = if (hideContent) {
                maskSpoilerText(text)
            } else {
                // Collapse ALL insignificant HTML whitespace — including source newlines used
                // purely for indentation (e.g. GitHub bridge messages) — into single spaces.
                // Per HTML, a newline inside text content is NOT a line break; real breaks come
                // from <br> (HtmlNode.LineBreak) and block-level tags. Block <pre>/code preserve
                // their own whitespace via collectRawText, so they aren't affected by this.
                var normalized = text.replace(Regex("\\s+"), " ")
                // Drop a leading space when it would be redundant: at the very start, right after a
                // <br>, or when the output already ends with whitespace/newline (e.g. after a list
                // bullet "• " or a block break). This collapses the source indentation between a
                // block tag and its inline content without eating meaningful inter-word spacing.
                if (normalized.startsWith(" ") && (length == 0 || previousWasLineBreak || endsWithWhitespace())) {
                    normalized = normalized.trimStart()
                }
                // After trimming, a node that was pure whitespace becomes empty — skip it. A single
                // space that survives is meaningful (e.g. between </strong> and <a>) and is kept.
                if (normalized.isEmpty()) {
                    return@appendHtmlNode
                }
                normalized
            }
            // Only append if text is not blank (shouldn't happen after normalization, but safety check)
            if (text.isNotEmpty()) {
                // If text doesn't contain newlines, append it directly without trimming
                // This preserves spaces before inline tags like <em> and <strong>
                if (!text.contains('\n')) {
                    withStyle(baseStyle) { append(text) }
                } else {
                    // Split by newlines and append each line separately, preserving newlines
                    // Note: We preserve trailing spaces on all lines because they might be important
                    // (e.g., spaces before inline tags). Only the last line gets trimmed if the
                    // original text ended with a newline (meaning that line was followed by a newline).
                    val lines = text.split('\n')
                    val originalEndsWithNewline = text.endsWith('\n')
                    lines.forEachIndexed { index, line ->
                        if (index > 0) {
                            // Add newline before each line except the first
                            append("\n")
                        }
                        // Only trim trailing whitespace from the last line if the original text
                        // ended with a newline (meaning this line was followed by a newline).
                        // For all other lines, preserve trailing spaces - they might be spaces
                        // before inline tags in the next text node.
                        val lineToAppend = if (index == lines.size - 1 && originalEndsWithNewline) {
                            // Last line and original ended with newline - safe to trim trailing space
                            line.trimEnd()
                        } else {
                            // Preserve trailing space - it might be before an inline tag
                            line
                        }
                        if (lineToAppend.isNotEmpty()) {
                            withStyle(baseStyle) { append(lineToAppend) }
                        }
                        // Note: Blank lines are preserved by the newline above, even if lineToAppend is empty
                    }
                }
            }
        }

        is HtmlNode.LineBreak -> append("\n")

        is HtmlNode.Tag -> appendHtmlTag(
            node,
            baseStyle,
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks,
        )
    }
}

private fun AnnotatedString.Builder.appendHtmlTag(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    val styleAttr = tag.attributes["style"]?.lowercase() ?: ""
    if (styleAttr.contains("display") && styleAttr.contains("none")) {
        return
    }

    val mxSpoilerReason = tag.attributes["data-mx-spoiler"]
    if (!hideContent && mxSpoilerReason != null && spoilerContext != null) {
        val sanitizedReason = mxSpoilerReason.takeIf { it.isNotBlank() }
        val filteredAttributes = tag.attributes.toMutableMap().apply { remove("data-mx-spoiler") }
        val tagWithoutSpoiler = tag.copy(attributes = filteredAttributes)
        appendSpoilerNodes(
            nodes = listOf(tagWithoutSpoiler),
            baseStyle = applyInlineColors(tag, baseStyle),
            inlineImages = inlineImages,
            inlineMatrixUsers = inlineMatrixUsers,
            inlineMatrixRooms = inlineMatrixRooms,
            spoilerContext = spoilerContext,
            reason = sanitizedReason,
            inlineCodeBlocks = inlineCodeBlocks,
        )
        return
    }

    val styledBase = applyInlineColors(tag, baseStyle)

    when (tag.name) {
        "strong", "b" -> appendStyledChildren(
            tag,
            styledBase.copy(fontWeight = FontWeight.Bold),
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks = inlineCodeBlocks,
        )

        "em", "i" -> appendStyledChildren(
            tag,
            styledBase.copy(fontStyle = FontStyle.Italic),
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks = inlineCodeBlocks,
        )

        "u" -> {
            val newStyle = styledBase.copy(
                textDecoration = (styledBase.textDecoration ?: TextDecoration.None) + TextDecoration.Underline,
            )
            appendStyledChildren(
                tag,
                newStyle,
                inlineImages,
                inlineMatrixUsers,
                inlineMatrixRooms,
                spoilerContext,
                hideContent,
                inlineCodeBlocks = inlineCodeBlocks,
            )
        }

        "s", "del", "strike" -> {
            val newStyle = styledBase.copy(
                textDecoration = (styledBase.textDecoration ?: TextDecoration.None) + TextDecoration.LineThrough,
            )
            appendStyledChildren(
                tag,
                newStyle,
                inlineImages,
                inlineMatrixUsers,
                inlineMatrixRooms,
                spoilerContext,
                hideContent,
                inlineCodeBlocks = inlineCodeBlocks,
            )
        }

        "ins" -> {
            val newStyle = styledBase.copy(
                textDecoration = (styledBase.textDecoration ?: TextDecoration.None) + TextDecoration.Underline,
            )
            appendStyledChildren(
                tag,
                newStyle,
                inlineImages,
                inlineMatrixUsers,
                inlineMatrixRooms,
                spoilerContext,
                hideContent,
                inlineCodeBlocks = inlineCodeBlocks,
            )
        }

        "code" -> appendStyledChildren(
            tag,
            styledBase.copy(fontFamily = FontFamily.Monospace),
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks = inlineCodeBlocks,
        )

        "span", "font" -> appendSpoilerOrStyledChildren(
            tag,
            styledBase,
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks,
        )

        "br" -> append("\n")

        "hr" -> appendHorizontalRule()

        "h1", "h2", "h3", "h4", "h5", "h6" -> appendHeader(
            tag,
            styledBase,
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks,
        )

        "p", "div" -> appendBlock(
            tag,
            styledBase,
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            inlineCodeBlocks,
        )

        "blockquote" -> appendBlockQuote(
            tag,
            styledBase,
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks,
        )

        "ul" -> appendUnorderedList(
            tag,
            styledBase,
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks,
        )

        "ol" -> appendOrderedList(
            tag,
            styledBase,
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks,
        )

        "a" -> appendAnchor(
            tag,
            styledBase,
            inlineImages,
            inlineMatrixUsers,
            inlineMatrixRooms,
            spoilerContext,
            hideContent,
            inlineCodeBlocks,
        )

        "img" -> appendImage(tag, inlineImages, hideContent)

        "hicli-math" -> appendInlineMath(tag, styledBase, inlineImages, hideContent)

        "pre" -> {
            // Check if this is a code block (has <code> inside <pre>)
            val hasCodeTag = tag.children.any { child ->
                child is HtmlNode.Tag && child.name == "code"
            }
            if (hasCodeTag && inlineCodeBlocks != null) {
                // This is a code block - render truncated preview
                appendCodeBlockPreview(
                    tag,
                    styledBase,
                    inlineImages,
                    inlineMatrixUsers,
                    inlineMatrixRooms,
                    inlineCodeBlocks,
                )
            } else {
                // Regular pre block
                appendPreformattedBlock(tag, styledBase, inlineImages, inlineMatrixUsers, inlineMatrixRooms)
            }
        }

        else -> tag.children.forEach {
            appendHtmlNode(
                it,
                styledBase,
                inlineImages,
                inlineMatrixUsers,
                inlineMatrixRooms,
                spoilerContext,
                hideContent,
                inlineCodeBlocks = inlineCodeBlocks,
            )
        }
    }
}

private fun AnnotatedString.Builder.appendStyledChildren(
    tag: HtmlNode.Tag,
    style: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    initialPreviousWasLineBreak: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    var previousWasLineBreak = initialPreviousWasLineBreak
    tag.children.forEach { child ->
        appendHtmlNode(child, style, inlineImages, inlineMatrixUsers, inlineMatrixRooms, spoilerContext, hideContent, previousWasLineBreak, inlineCodeBlocks)
        previousWasLineBreak = child is HtmlNode.LineBreak
    }
}

/**
 * Extract spoiler data from a list of HTML nodes (handles sibling spoiler-reason and hicli-spoiler spans)
 */
private fun extractSpoilerData(nodes: List<HtmlNode>): Pair<String, List<HtmlNode>>? {
    var reason: String? = null
    var contentNodes: List<HtmlNode>? = null

    for (node in nodes) {
        if (node is HtmlNode.Tag && node.name == "span") {
            val classAttr = node.attributes["class"] ?: ""

            if (classAttr.contains("spoiler-reason")) {
                val reasonBuilder = StringBuilder()
                node.children.forEach { collectPlainText(it, reasonBuilder) }
                reason = reasonBuilder.toString().trim().takeIf { it.isNotEmpty() }
            } else if (classAttr.contains("hicli-spoiler")) {
                contentNodes = node.children.takeIf { it.isNotEmpty() }
            }
        }
    }

    return if (!reason.isNullOrEmpty() && contentNodes != null) {
        Pair(reason, contentNodes)
    } else {
        null
    }
}

/**
 * Extract text content from HTML nodes, preserving structure for spoiler content
 * For links, shows the href URL if available, otherwise the link text
 */

/**
 * Handle span tags - check if they're spoilers or regular spans
 */
private fun AnnotatedString.Builder.appendSpoilerOrStyledChildren(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    val classAttr = tag.attributes["class"] ?: ""

    // Check if this is a spoiler reason span - skip it, will be handled with hicli-spoiler
    if (classAttr.contains("spoiler-reason")) {
        return
    }

    // Check if this is a spoiler content span
    if (classAttr.contains("hicli-spoiler")) {
        if (spoilerContext != null && tag.children.isNotEmpty()) {
            appendSpoilerNodes(
                nodes = tag.children,
                baseStyle = baseStyle,
                inlineImages = inlineImages,
                inlineMatrixUsers = inlineMatrixUsers,
                inlineMatrixRooms = inlineMatrixRooms,
                spoilerContext = spoilerContext,
                reason = null,
                inlineCodeBlocks = inlineCodeBlocks,
            )
            return
        }
    }

    // Regular span/font - process children with optional color/background
    val styled = applyInlineColors(tag, baseStyle)
    appendStyledChildren(
        tag,
        styled,
        inlineImages,
        inlineMatrixUsers,
        inlineMatrixRooms,
        spoilerContext,
        hideContent,
        inlineCodeBlocks = inlineCodeBlocks,
    )
}

private fun AnnotatedString.Builder.appendBlock(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    if (length > 0 && !endsWithNewline()) append("\n")

    // Process children, handling spoiler patterns inline
    var i = 0
    var previousWasLineBreak = false
    while (i < tag.children.size) {
        val child = tag.children[i]

        // Check if this and next child form a spoiler pattern
        if (i + 1 < tag.children.size) {
            val spoilerData = extractSpoilerData(listOf(child, tag.children[i + 1]))
            if (spoilerData != null) {
                val (reason, contentNodes) = spoilerData
                if (contentNodes.isNotEmpty() && spoilerContext != null) {
                    appendSpoilerNodes(
                        nodes = contentNodes,
                        baseStyle = baseStyle,
                        inlineImages = inlineImages,
                        inlineMatrixUsers = inlineMatrixUsers,
                        inlineMatrixRooms = inlineMatrixRooms,
                        spoilerContext = spoilerContext,
                        reason = reason,
                        inlineCodeBlocks = inlineCodeBlocks,
                    )
                } else {
                    var wasLineBreak = previousWasLineBreak
                    contentNodes.forEach {
                        appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers, inlineMatrixRooms, null, hideContent = false, previousWasLineBreak = wasLineBreak, inlineCodeBlocks = inlineCodeBlocks)
                        wasLineBreak = it is HtmlNode.LineBreak
                    }
                    previousWasLineBreak = wasLineBreak
                }
                i += 2 // Skip both nodes
                continue
            }
        }

        // No spoiler pattern, process normally
        appendHtmlNode(child, baseStyle, inlineImages, inlineMatrixUsers, inlineMatrixRooms, spoilerContext, hideContent = false, previousWasLineBreak = previousWasLineBreak, inlineCodeBlocks = inlineCodeBlocks)
        previousWasLineBreak = child is HtmlNode.LineBreak
        i++
    }
    // Keep normal <p>/<div> separation as a single newline.
    // Extra blank lines between consecutive paragraphs are handled in HtmlMessageText
    // (so we don't accidentally insert blanks before other block elements like <blockquote>).
    append("\n")
}

private fun AnnotatedString.Builder.appendHeader(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    if (length > 0 && !endsWithNewline()) append("\n")

    // Apply header styling - bold text
    // Note: We use just bold for now to avoid fontSize issues with TextUnit.Unspecified
    val headerStyle = baseStyle.copy(
        fontWeight = FontWeight.Bold,
    )

    tag.children.forEach {
        appendHtmlNode(it, headerStyle, inlineImages, inlineMatrixUsers, inlineMatrixRooms, spoilerContext, hideContent, previousWasLineBreak = false, inlineCodeBlocks = inlineCodeBlocks)
    }
    append("\n")
}

private fun AnnotatedString.Builder.endsWithNewline(): Boolean {
    if (length == 0) return false
    return this.toAnnotatedString().text.last() == '\n'
}

private fun AnnotatedString.Builder.appendBlockQuote(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
    quoteDepth: Int = 1,
) {
    if (length > 0 && !endsWithNewline()) append("\n")

    // Render quotes with a depth-based prefix:
    // depth=1 => "| Quote"
    // depth=2 => "|| Double Quote"
    // ...etc
    // The prefix is tertiary-colored; the quoted content is rendered with the same styling
    // as normal text (so bold/italic/underline/strikethrough/spoilers keep working).
    // Use a vertical box-drawing character so nested quotes align nicely.
    // Depth=1 => "│ " ; Depth=2 => "││ " ; etc.
    val prefixText = "│".repeat(quoteDepth) + " "
    // Prefix should visually match the regular text (same color/typeface).
    val prefixStyle = baseStyle

    fun appendQuoteLineContent(children: List<HtmlNode>) {
        // Render quoted content while prefixing after <br> as well.
        children.forEach { child ->
            when (child) {
                is HtmlNode.LineBreak -> {
                    append("\n")
                    withStyle(prefixStyle) { append(prefixText) }
                }

                else -> {
                    appendHtmlNode(
                        node = child,
                        baseStyle = baseStyle,
                        inlineImages = inlineImages,
                        inlineMatrixUsers = inlineMatrixUsers,
                        inlineMatrixRooms = inlineMatrixRooms,
                        spoilerContext = spoilerContext,
                        hideContent = hideContent,
                        previousWasLineBreak = false,
                        inlineCodeBlocks = inlineCodeBlocks,
                    )
                }
            }
        }
    }

    // A leading/trailing <br> inside the quote would open a prefixed line with nothing on it.
    tag.children.trimEdgeLineBreaks().forEach { child ->
        when (child) {
            // Most Matrix quote payloads are structured as <blockquote><p>... (nested) ...</blockquote>
            // Render each <p> / <div> as one quoted "line".
            is HtmlNode.Tag -> {
                when (child.name) {
                    "p", "div" -> {
                        if (length > 0 && !endsWithNewline()) append("\n")
                        withStyle(prefixStyle) { append(prefixText) }
                        appendQuoteLineContent(child.children.trimEdgeLineBreaks())
                        if (!endsWithNewline()) append("\n")
                    }

                    "blockquote" -> {
                        appendBlockQuote(
                            tag = child,
                            baseStyle = baseStyle,
                            inlineImages = inlineImages,
                            inlineMatrixUsers = inlineMatrixUsers,
                            inlineMatrixRooms = inlineMatrixRooms,
                            spoilerContext = spoilerContext,
                            hideContent = hideContent,
                            inlineCodeBlocks = inlineCodeBlocks,
                            quoteDepth = quoteDepth + 1,
                        )
                    }

                    else -> {
                        // Fallback: render unknown block children normally (without prefixing).
                        // This keeps the formatter accurate for edge cases like nested lists.
                        appendHtmlNode(
                            node = child,
                            baseStyle = baseStyle,
                            inlineImages = inlineImages,
                            inlineMatrixUsers = inlineMatrixUsers,
                            inlineMatrixRooms = inlineMatrixRooms,
                            spoilerContext = spoilerContext,
                            hideContent = hideContent,
                            inlineCodeBlocks = inlineCodeBlocks,
                        )
                    }
                }
            }

            // Rare case: text directly inside <blockquote>.
            // Treat it as a single-line quote.
            is HtmlNode.Text -> {
                val trimmed = child.content.trim()
                if (trimmed.isNotEmpty()) {
                    if (length > 0 && !endsWithNewline()) append("\n")
                    withStyle(prefixStyle) { append(prefixText) }
                    appendHtmlNode(
                        node = child,
                        baseStyle = baseStyle,
                        inlineImages = inlineImages,
                        inlineMatrixUsers = inlineMatrixUsers,
                        inlineMatrixRooms = inlineMatrixRooms,
                        spoilerContext = spoilerContext,
                        hideContent = hideContent,
                        previousWasLineBreak = false,
                        inlineCodeBlocks = inlineCodeBlocks,
                    )
                    if (!endsWithNewline()) append("\n")
                }
            }

            is HtmlNode.LineBreak -> {
                // If the quote has a raw line break, mirror it with a new prefixed line.
                append("\n")
                withStyle(prefixStyle) { append(prefixText) }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendUnorderedList(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    append("\n")
    tag.children.forEach { child ->
        if (child is HtmlNode.Tag && child.name == "li") {
            append("• ")
            child.children.forEach {
                appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers, inlineMatrixRooms, spoilerContext, hideContent, previousWasLineBreak = false, inlineCodeBlocks = inlineCodeBlocks)
            }
            append("\n")
        }
    }
}

private fun AnnotatedString.Builder.appendSpoilerNodes(
    nodes: List<HtmlNode>,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext,
    reason: String?,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    val spoilerId = spoilerContext.nextId()
    val revealed = spoilerContext.isRevealed(spoilerId)

    val start = length
    if (reason != null) {
        withStyle(baseStyle.copy(fontStyle = FontStyle.Italic)) {
            append("($reason) ")
        }
    }
    nodes.forEach {
        appendHtmlNode(
            node = it,
            baseStyle = baseStyle,
            inlineImages = inlineImages,
            inlineMatrixUsers = inlineMatrixUsers,
            inlineMatrixRooms = inlineMatrixRooms,
            spoilerContext = spoilerContext,
            hideContent = !revealed,
            previousWasLineBreak = false,
            inlineCodeBlocks = inlineCodeBlocks,
        )
    }
    val end = length
    addStringAnnotation("SPOILER", spoilerId, start, end)
}

private fun AnnotatedString.Builder.appendOrderedList(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    append("\n")
    var index = 1
    tag.children.forEach { child ->
        if (child is HtmlNode.Tag && child.name == "li") {
            append("$index. ")
            child.children.forEach {
                appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers, inlineMatrixRooms, spoilerContext, hideContent, previousWasLineBreak = false, inlineCodeBlocks = inlineCodeBlocks)
            }
            append("\n")
            index++
        }
    }
}

private fun AnnotatedString.Builder.appendHorizontalRule() {
    if (length > 0 && !endsWithNewline()) append("\n")
    append("────────")
    append("\n")
}

private fun AnnotatedString.Builder.appendPreformattedBlock(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
) {
    if (length > 0 && !endsWithNewline()) append("\n")
    val rawText = buildString {
        collectRawText(tag, this)
    }.let { text ->
        if (text.endsWith("\n")) text.dropLast(1) else text
    }
    withStyle(baseStyle.copy(fontFamily = FontFamily.Monospace)) {
        append(rawText)
    }
    append("\n")
}

/**
 * Render a truncated code block preview (8 lines) with clickable annotation
 */
private fun AnnotatedString.Builder.appendCodeBlockPreview(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>,
) {
    if (length > 0 && !endsWithNewline()) append("\n")

    // Extract full code text
    val fullCode = buildString {
        collectRawText(tag, this)
    }.let { text ->
        if (text.endsWith("\n")) text.dropLast(1) else text
    }

    // Split into lines and truncate to 8 lines if needed
    val lines = fullCode.lines()
    val previewLines = if (lines.size > 8) {
        lines.take(8)
    } else {
        lines
    }
    val previewText = previewLines.joinToString("\n")

    // Generate unique ID for this code block
    val codeBlockId = "code_${inlineCodeBlocks.size}_${fullCode.hashCode()}"
    inlineCodeBlocks[codeBlockId] = InlineCodeBlockPreview(
        previewText = previewText,
        fullCode = fullCode,
        totalLines = lines.size,
    )

    // Render the truncated code directly in the text flow with monospace style
    val codeStyle = baseStyle.copy(fontFamily = FontFamily.Monospace)
    val annotationStart = length
    pushStringAnnotation("CODE_BLOCK", codeBlockId)
    withStyle(codeStyle) {
        append(previewText)
        // Add truncation indicator if needed
        if (lines.size > 8) {
            append("\n... (${lines.size - 8} more lines, tap to view full code)")
        }
    }
    pop()
    val annotationEnd = length
    append("\n")
}

private fun collectRawText(node: HtmlNode, builder: StringBuilder) {
    when (node) {
        is HtmlNode.Text -> builder.append(node.content)

        is HtmlNode.LineBreak -> builder.append('\n')

        is HtmlNode.Tag -> {
            if (node.name == "br") {
                builder.append('\n')
            } else {
                node.children.forEach { collectRawText(it, builder) }
            }
        }
    }
}

private fun applyInlineColors(tag: HtmlNode.Tag, baseStyle: SpanStyle): SpanStyle {
    val styleAttr = tag.attributes["style"]
    val textColorRaw = tag.attributes["data-mx-color"]
        ?: tag.attributes["color"]
        ?: styleAttr?.let { extractStyleValue(it, "color") }
    val bgColorRaw = tag.attributes["data-mx-bg-color"]
        ?: styleAttr?.let { extractStyleValue(it, "background-color") }

    var style = baseStyle
    parseCssColor(textColorRaw)?.let { style = style.copy(color = it) }
    parseCssColor(bgColorRaw)?.let { style = style.copy(background = it) }
    return style
}

private fun extractStyleValue(styleAttr: String, key: String): String? {
    // Use negative lookbehind (?<![a-zA-Z-]) to prevent matching property names that are
    // suffixes of longer names (e.g. "color" should NOT match inside "background-color").
    val regex = Regex("""(?i)(?<![a-zA-Z-])${Regex.escape(key)}\s*:\s*([^;]+)""")
    return regex.find(styleAttr)?.groupValues?.getOrNull(1)?.trim()
}

private fun parseCssColor(raw: String?): Color? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        Color(AndroidColor.parseColor(value))
    } catch (_: IllegalArgumentException) {
        null
    }
}

private val plainUrlRegex = Regex("""(?i)\bhttps?://[^\s<>()]+""")
private val trailingUrlPunctuation = setOf('.', ',', ':', ';', '!', '?', ')', ']', '}', '"', '\'')

private fun buildPlainTextAnnotatedString(text: String, linkStyle: SpanStyle): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        var lastIndex = 0
        plainUrlRegex.findAll(text).forEach { match ->
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (start > lastIndex) {
                append(text.substring(lastIndex, start))
            }

            var trimmedEnd = endExclusive
            while (trimmedEnd > start && text[trimmedEnd - 1] in trailingUrlPunctuation) {
                trimmedEnd--
            }

            if (trimmedEnd > start) {
                val url = text.substring(start, trimmedEnd)
                pushStringAnnotation("URL", url)
                withStyle(linkStyle) { append(url) }
                pop()
                if (trimmedEnd < endExclusive) {
                    append(text.substring(trimmedEnd, endExclusive))
                }
            } else {
                append(text.substring(start, endExclusive))
            }

            lastIndex = endExclusive
        }

        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

private fun buildPlainTextAnnotatedStringWithCode(text: String, linkStyle: SpanStyle, codeStyle: SpanStyle): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        var inCodeBlock = false
        var hasOutputLine = false
        var lastOutputWasBlank = false

        fun appendLine(line: AnnotatedString, isBlank: Boolean) {
            if (hasOutputLine) {
                append("\n")
            }
            if (!isBlank) {
                append(line)
            }
            hasOutputLine = true
            lastOutputWasBlank = isBlank
        }

        fun appendBlankLine(allowDuplicate: Boolean) {
            if (!allowDuplicate && lastOutputWasBlank) return
            appendLine(AnnotatedString(""), isBlank = true)
        }

        val lines = text.split('\n')
        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                if (!inCodeBlock) {
                    appendBlankLine(allowDuplicate = false)
                } else {
                    appendBlankLine(allowDuplicate = false)
                }
                inCodeBlock = !inCodeBlock
                continue
            }

            if (line.isEmpty()) {
                appendBlankLine(allowDuplicate = true)
                continue
            }

            if (inCodeBlock) {
                appendLine(AnnotatedString(line), isBlank = false)
                addStyle(codeStyle, length - line.length, length)
            } else {
                val annotatedLine = buildPlainTextAnnotatedString(line, linkStyle)
                appendLine(annotatedLine, isBlank = false)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendAnchor(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    inlineMatrixRooms: MutableMap<String, InlineMatrixRoomChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null,
) {
    val href = tag.attributes["href"] ?: ""
    val classAttr = tag.attributes["class"] ?: ""

    // Check for Matrix user links first
    val matrixUser = extractMatrixUserId(href)
    if (matrixUser != null) {
        val textBuilder = StringBuilder()
        tag.children.forEach { collectPlainText(it, textBuilder) }
        var displayText = textBuilder.toString().ifBlank { matrixUser }
        // For hidden spoilers, render masked text and skip inline content/annotations
        if (hideContent) {
            append(maskSpoilerText(displayText))
            if (!endsWithWhitespace()) {
                append(" ")
            }
            return
        }

        val chipId = "matrix_user_${inlineMatrixUsers.size}"
        inlineMatrixUsers[chipId] = InlineMatrixUserChip(matrixUser, displayText)
        pushStringAnnotation("MATRIX_USER", matrixUser)
        appendInlineContent(chipId, displayText)
        pop()
        // Add space after the user mention if not already ending with whitespace
        if (!endsWithWhitespace()) {
            append(" ")
        }
        return
    }

    // Check for Matrix room links - either by class or by extractRoomLink
    val isRoomLink = classAttr.contains("hicli-matrix-uri") || extractRoomLink(href) != null
    val roomLink = if (isRoomLink) extractRoomLink(href) else null

    if (roomLink != null) {
        val textBuilder = StringBuilder()
        tag.children.forEach { collectPlainText(it, textBuilder) }
        val displayText = textBuilder.toString().ifBlank { roomLink.roomIdOrAlias }

        // For hidden spoilers, render masked text and skip inline content/annotations
        if (hideContent) {
            append(maskSpoilerText(displayText))
            if (!endsWithWhitespace()) {
                append(" ")
            }
            return
        }

        // Render as a pill (chip) similar to user mentions
        val chipId = "matrix_room_${inlineMatrixRooms.size}"
        inlineMatrixRooms[chipId] = InlineMatrixRoomChip(
            roomLink = roomLink,
            displayText = displayText,
            isJoined = false, // Will be determined by the caller if needed
            roomName = null,
            roomAvatarUrl = null,
        )
        pushStringAnnotation("ROOM_LINK", href)
        appendInlineContent(chipId, displayText)
        pop()
        // Add space after the room link if not already ending with whitespace
        if (!endsWithWhitespace()) {
            append(" ")
        }
        return
    }

    // Regular URL - only add annotation if href is not empty
    if (href.isNotBlank()) {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "appendAnchor: Adding URL annotation for href=$href")
        val linkStyle = baseStyle.copy(color = Color(0xFF1A73E8), textDecoration = TextDecoration.Underline)
        val annotationStart = length
        pushStringAnnotation("URL", href)
        var previousWasLineBreak = false
        tag.children.forEach { child ->
            appendHtmlNode(child, linkStyle, inlineImages, inlineMatrixUsers, inlineMatrixRooms, spoilerContext, hideContent, previousWasLineBreak, inlineCodeBlocks = inlineCodeBlocks)
            previousWasLineBreak = child is HtmlNode.LineBreak
        }
        val annotationEnd = length
        pop()
        if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "appendAnchor: URL annotation added from $annotationStart to $annotationEnd for href=$href",
            )
        }
    } else {
        if (BuildConfig.DEBUG) Log.w("Andromuks", "appendAnchor: Empty href, not adding URL annotation")
        // No href, just render children with base style (shouldn't happen for valid HTML)
        var previousWasLineBreak = false
        tag.children.forEach { child ->
            appendHtmlNode(
                child,
                baseStyle,
                inlineImages,
                inlineMatrixUsers,
                inlineMatrixRooms,
                spoilerContext,
                hideContent,
                previousWasLineBreak,
            )
            previousWasLineBreak = child is HtmlNode.LineBreak
        }
    }
}

/**
 * Pull `width`/`height` out of an inline style, in px.
 *
 * gomuks' sanitizer does not preserve an `<img>`'s size attributes — it rewrites the tag and
 * expresses the size as CSS instead:
 *
 * ```
 * class="hicli-inline-img hicli-sized-inline-img" style="width: 320.00px; height: 99.00px;"
 * ```
 *
 * (A custom emoji gets `hicli-custom-emoji` and no style at all, which is why an absent size is
 * also the signal that an image belongs on the text line.) Reading only the attributes meant every
 * image in a server-rendered profile bio arrived sizeless and was drawn as a line-height square.
 *
 * Non-px units are ignored: they need a layout context to resolve, and gomuks only ever emits px.
 */
internal fun parseCssImageSizePx(style: String?): Pair<Int?, Int?> {
    if (style.isNullOrBlank()) return null to null
    var width: Int? = null
    var height: Int? = null
    cssPxSizeRegex.findAll(style).forEach { match ->
        val value = match.groupValues[2].toFloatOrNull()?.roundToInt()?.takeIf { it > 0 } ?: return@forEach
        when (match.groupValues[1].lowercase()) {
            "width" -> width = value
            "height" -> height = value
        }
    }
    return width to height
}

private val cssPxSizeRegex = Regex("""(?:^|;)\s*(width|height)\s*:\s*(\d*\.?\d+)\s*px""", RegexOption.IGNORE_CASE)

/**
 * One piece of a document-style HTML body: either markup to run through the normal renderer, or a
 * real image to lay out on its own. See [splitTopLevelBlockImages].
 */
sealed interface HtmlSegment {
    data class Markup(val html: String) : HtmlSegment

    data class BlockImage(val src: String, val alt: String, val width: Int, val height: Int) : HtmlSegment
}

/**
 * Split a body into runs of markup and the top-level images that should be laid out as blocks.
 *
 * A picture cannot be rendered as inline text content. Inline content is a [Placeholder] inside a
 * `Text`, and the line it lands on takes its height from the text style's `lineHeight` — so a
 * 99dp-tall placeholder overflows a 17dp line and the following text is laid out, and drawn,
 * straight through it. No choice of `PlaceholderVerticalAlign` fixes that; both `TextCenter` and
 * `Center` were tried against a real bio.
 *
 * Which images qualify: a **declared size** is the test. gomuks emits one for a real image
 * (`style="width: 320.00px; height: 99.00px"`) and none at all for a custom emoji, so the two are
 * distinguishable without any marker attribute — which matters, because the `data-mx-emoticon` an
 * earlier attempt keyed on does not survive sanitization. An emoji stays in the text where it
 * belongs. Only top-level images are pulled out; one nested inside a paragraph or a link keeps its
 * place in the flow.
 *
 * Order is preserved: the segments come back in document order, so a banner above the text renders
 * above the text.
 */
internal fun splitTopLevelBlockImages(html: String): List<HtmlSegment> {
    val body = runCatching { Jsoup.parseBodyFragment(html).body() }.getOrNull() ?: return listOf(HtmlSegment.Markup(html))
    val segments = mutableListOf<HtmlSegment>()
    val markup = StringBuilder()

    fun flushMarkup() {
        val pending = markup.toString()
        // A <br> that only separated an extracted image from what follows would render as a blank
        // first line, so leading breaks are dropped from each run.
        val trimmed = pending.trim().removeLeadingLineBreaks()
        if (trimmed.isNotBlank()) segments.add(HtmlSegment.Markup(trimmed))
        markup.setLength(0)
    }

    body.childNodes().forEach { node ->
        val blockImage = (node as? Element)?.takeIf { it.tagName().lowercase() == "img" }?.toBlockImage()
        if (blockImage != null) {
            flushMarkup()
            segments.add(blockImage)
        } else {
            markup.append(node.outerHtml())
        }
    }
    flushMarkup()
    return segments
}

private fun Element.toBlockImage(): HtmlSegment.BlockImage? {
    val src = (attr("src").takeIf { it.isNotBlank() } ?: attr("data-mxc")).takeIf { it.isNotBlank() } ?: return null
    // An http(s) src would leak the reader's IP to a third-party host, the same rule the parser
    // applies. Such an image is left in the markup run, where it becomes its alt text.
    if (src.startsWith("http://") || src.startsWith("https://")) return null
    val (styleWidth, styleHeight) = parseCssImageSizePx(attr("style"))
    val width = attr("width").toIntOrNull() ?: styleWidth ?: return null
    val height = attr("height").toIntOrNull() ?: styleHeight ?: return null
    if (width <= 0 || height <= 0) return null
    return HtmlSegment.BlockImage(
        src = src,
        alt = attr("alt").takeIf { it.isNotBlank() } ?: attr("title"),
        width = width,
        height = height,
    )
}

private val leadingLineBreakRegex = Regex("""^(?:\s*<br\s*/?>)+""", RegexOption.IGNORE_CASE)

private fun String.removeLeadingLineBreaks(): String = replace(leadingLineBreakRegex, "").trimStart()

private fun AnnotatedString.Builder.appendImage(tag: HtmlNode.Tag, inlineImages: MutableMap<String, InlineImageData>, hideContent: Boolean) {
    val src = tag.attributes["src"] ?: tag.attributes["data-mxc"] ?: ""
    val alt = tag.attributes["alt"] ?: tag.attributes["title"] ?: ""
    // Attributes first (a client's own formatted_body keeps them), then the sanitizer's CSS.
    val (styleWidth, styleHeight) = parseCssImageSizePx(tag.attributes["style"])
    val declaredHeight = tag.attributes["height"]?.toIntOrNull() ?: styleHeight
    val declaredWidth = tag.attributes["width"]?.toIntOrNull() ?: styleWidth
    val height = declaredHeight ?: 32
    if (src.isNotBlank()) {
        val id = "inline_img_${inlineImages.size}"
        inlineImages[id] = InlineImageData(
            src,
            alt,
            height,
            isHidden = hideContent,
            declaredWidth = declaredWidth,
            declaredHeight = declaredHeight,
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                // The declared values are logged raw, null included: `height` below substitutes a
                // default, so it cannot tell an absent attribute from a declared height="32" —
                // which is exactly the distinction every sizing bug here has turned on.
                "HtmlParser: Added inline image id=$id, src=$src, alt=$alt, height=$height, " +
                    "declared=${declaredWidth}x$declaredHeight",
            )
        }
        appendInlineContent(id, "\u200B")
    } else {
        if (BuildConfig.DEBUG) Log.w("Andromuks", "HtmlParser: Image tag has no src attribute, using alt text: $alt")
        append(if (hideContent) maskSpoilerText(alt) else alt)
    }
}

/**
 * MSC2191: extract the LaTeX source from a <hicli-math> tag. Prefers the `latex` attribute
 * (decoded form gomuks provides), falling back to the raw text of the inner <code> element.
 */
private fun extractMathLatex(tag: HtmlNode.Tag): String {
    // The `latex` attribute is not a leaf text node, so the parser hasn't decoded it. LaTeX that
    // legitimately contains '&', '<', or '>' arrives escaped in the attribute — decode it here.
    val attr = tag.attributes["latex"]?.takeIf { it.isNotBlank() }
    if (attr != null) return decodeHtmlEntities(attr)
    // collectRawText already yields decoded leaf text (parser decodes entities), so don't re-decode.
    return buildString { collectRawText(tag, this) }.trim()
}

/**
 * Render a <hicli-math> node as inline content (baseline-aligned, sized to the surrounding text).
 * The actual LaTeX-to-bitmap rendering happens in HtmlMessageText's inlineContent map via
 * JLaTeXMath; here we only register the placeholder. Top-level *block* math is pulled out and
 * centered before this is reached, so anything arriving here is treated as inline.
 */
private fun AnnotatedString.Builder.appendInlineMath(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    hideContent: Boolean,
) {
    val latex = extractMathLatex(tag)
    if (latex.isBlank()) return
    if (hideContent) {
        // Inside a hidden spoiler: don't reveal the equation, mask its source length.
        withStyle(baseStyle.copy(fontFamily = FontFamily.Monospace)) {
            append(maskSpoilerText(latex))
        }
        return
    }
    val id = "inline_math_${inlineImages.size}"
    inlineImages[id] = InlineImageData(src = "", alt = latex, height = 0, latex = latex)
    appendInlineContent(id, "\u200B")
}

private fun collectPlainText(node: HtmlNode, builder: StringBuilder) {
    when (node) {
        is HtmlNode.Text -> builder.append(node.content)
        is HtmlNode.LineBreak -> builder.append(' ')
        is HtmlNode.Tag -> node.children.forEach { collectPlainText(it, builder) }
    }
}

/**
 * Decode HTML entities in a string
 */
fun decodeHtmlEntities(html: String): String {
    var result = html

    // Decode numeric character references (&#xxx; and &#xHH;)
    result = result.replace(Regex("&#(\\d+);")) { matchResult ->
        val code = matchResult.groupValues[1].toIntOrNull()
        if (code != null) {
            code.toChar().toString()
        } else {
            matchResult.value
        }
    }

    result = result.replace(Regex("&#[xX]([0-9a-fA-F]+);")) { matchResult ->
        val code = matchResult.groupValues[1].toIntOrNull(16)
        if (code != null) {
            code.toChar().toString()
        } else {
            matchResult.value
        }
    }

    // Decode named character entities (most common ones)
    val namedEntities = mapOf(
        "&quot;" to "\"",
        "&quot" to "\"", // Also without semicolon
        "&apos;" to "'",
        "&apos" to "'", // Also without semicolon
        "&amp;" to "&",
        "&amp" to "&", // Also without semicolon
        "&lt;" to "<",
        "&lt" to "<", // Also without semicolon
        "&gt;" to ">",
        "&gt" to ">", // Also without semicolon
        "&nbsp;" to " ",
        "&nbsp" to " ", // Also without semicolon
        "&copy;" to "©",
        "&copy" to "©", // Also without semicolon
        "&reg;" to "®",
        "&reg" to "®", // Also without semicolon
        "&euro;" to "€",
        "&euro" to "€", // Also without semicolon
        "&pound;" to "£",
        "&pound" to "£", // Also without semicolon
        "&yen;" to "¥",
        "&yen" to "¥", // Also without semicolon
        "&cent;" to "¢",
        "&cent" to "¢", // Also without semicolon
    )

    // Sort by length (longest first) to avoid partial replacements
    namedEntities.entries.sortedByDescending { it.key.length }.forEach { (entity, char) ->
        result = result.replace(entity, char)
    }

    return result
}

/**
 * Extract sanitized HTML from a timeline event
 */
fun extractSanitizedHtml(event: TimelineEvent): String? {
    // Check if event has local_content with sanitized_html
    // local_content is a top-level field in the event JSON, parsed into TimelineEvent.localContent
    // Return the raw sanitized HTML. Entity decoding happens inside HtmlParser at the
    // leaf text nodes — decoding here (before parsing) would turn escaped literals like
    // "&lt;tag&gt;" into "<tag>" and the parser would then drop them as unknown markup.
    return event.localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
}

private fun hasReplyFallback(event: TimelineEvent): Boolean {
    val content = event.decrypted ?: event.content ?: return false
    val relates = content.optJSONObject("m.relates_to") ?: return false
    val inReplyTo = relates.optJSONObject("m.in_reply_to") ?: return false
    return inReplyTo.optString("event_id").isNotBlank()
}

private fun stripReplyFallback(body: String): String {
    if (body.isEmpty()) return body
    val lines = body.split('\n')
    if (lines.isEmpty() || !lines.first().startsWith(">")) return body

    var index = 0
    while (index < lines.size && lines[index].startsWith(">")) {
        index++
    }
    if (index < lines.size && lines[index].isBlank()) {
        index++
    }
    val stripped = lines.drop(index).joinToString("\n")
    return if (stripped.isNotBlank()) stripped else body
}

/**
 * Check if event supports HTML rendering
 */
fun supportsHtmlRendering(event: TimelineEvent): Boolean {
    // If we already have sanitized HTML (from was_plaintext events), use it regardless of format/msgtype
    val sanitized = extractSanitizedHtml(event)
    if (sanitized != null) {
        return true
    }

    val content = when {
        event.decrypted != null -> event.decrypted
        event.content != null -> event.content
        else -> return false
    }

    // Otherwise fall back to formatted_body rules
    if (content.optString("format", "") != "org.matrix.custom.html") {
        return false
    }

    val msgType = content.optString("msgtype", "")
    val supportedTypes = setOf(
        "m.text",
        "m.emote",
        "m.notice",
        "m.image",
        "m.file",
        "m.audio",
        "m.video",
    )

    val hasFormattedBody = content.optString("formatted_body").takeIf { it.isNotEmpty() }?.isNotBlank() == true

    return msgType in supportedTypes && hasFormattedBody
}

/**
 * Extract Matrix user IDs from HTML nodes for opportunistic profile loading
 */
private fun extractMatrixUserIdsFromNodes(nodes: List<HtmlNode>): Set<String> {
    val userIds = mutableSetOf<String>()

    fun processNode(node: HtmlNode) {
        when (node) {
            is HtmlNode.Text -> {
                // Check for Matrix user links in text content
                val matrixUserRegex = Regex("""https://matrix\.to/#/(@[^)]+)""")
                matrixUserRegex.findAll(node.content).forEach { match ->
                    val encoded = match.groupValues[1]
                    val decoded = runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()
                    if (decoded != null && decoded.startsWith("@")) {
                        userIds.add(decoded)
                    }
                }
            }

            is HtmlNode.Tag -> {
                // Check for Matrix user links in anchor tags
                if (node.name == "a") {
                    val href = node.attributes["href"] ?: ""
                    val matrixUser = extractMatrixUserId(href)
                    if (matrixUser != null) {
                        userIds.add(matrixUser)
                    }
                }
                // Process children recursively
                node.children.forEach { processNode(it) }
            }

            is HtmlNode.LineBreak -> {
                // No user IDs in line breaks
            }
        }
    }

    nodes.forEach { processNode(it) }
    return userIds
}

/**
 * Composable for rendering inline spoiler text with masked text and tap-to-reveal
 * This renders spoilers inline within the message text, not as block elements
 */

/**
 * Renders Matrix HTML that did not arrive as a timeline event — profile biographies, for
 * instance, which carry exactly the same markup a message body does (custom emoticons via
 * `<img data-mx-emoticon src="mxc://…">`, formatting, links, spoilers).
 *
 * [HtmlMessageText] does all of that already but is keyed on a [TimelineEvent], so this wraps
 * the html in a synthetic one. The event only supplies the redaction check, a logging id and,
 * for the opportunistic profile fetch, a room id — none of which apply here, so the fetch is
 * disabled by leaving `appViewModel` null rather than fetching against an empty room id.
 */
@Composable
fun HtmlBodyText(
    html: String,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onMatrixUserClick: (String) -> Unit = {},
    onRoomLinkClick: (RoomLink) -> Unit = {},
    onInlineImageClick: (InlineImageData) -> Unit = {},
    inlineImageSizing: InlineImageSizing? = null,
) {
    val syntheticEvent = remember(html) {
        TimelineEvent(
            rowid = 0L,
            timelineRowid = 0L,
            roomId = "",
            eventId = "\$synthetic-html-body",
            sender = "",
            type = "m.room.message",
            timestamp = 0L,
            content = null,
        )
    }
    HtmlMessageText(
        event = syntheticEvent,
        homeserverUrl = homeserverUrl,
        authToken = authToken,
        modifier = modifier,
        color = color,
        onMatrixUserClick = onMatrixUserClick,
        onRoomLinkClick = onRoomLinkClick,
        htmlContent = html,
        onInlineImageClick = onInlineImageClick,
        inlineImageSizing = inlineImageSizing,
    )
}

/**
 * Composable function to render HTML content from an event
 */
@Composable
fun HtmlMessageText(
    event: TimelineEvent,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onMatrixUserClick: (String) -> Unit = {},
    onRoomLinkClick: (RoomLink) -> Unit = {},
    appViewModel: AppViewModel? = null,
    isEmojiOnly: Boolean = false,
    htmlContent: String? = null, // Optional HTML content (e.g., from edit) to override event extraction
    onCodeBlockClick: (String) -> Unit = {}, // Callback for code block clicks
    onInlineImageClick: (InlineImageData) -> Unit = {
    }, // Callback for tapping an mxc-backed inline image (custom emoji / inline <img>)
    // When set, inline <img> render at their declared size within these bounds instead of being
    // clamped to one line of text. Only for full-size markup views (see [InlineImageSizing]).
    inlineImageSizing: InlineImageSizing? = null,
) {
    // Don't render HTML for redacted messages
    // The parent composable should handle showing the deletion message
    if (event.redactedBy != null) {
        return
    }

    val context = LocalContext.current
    val sanitizedHtml = remember(event, htmlContent) {
        // Use provided htmlContent if available (e.g., from edit), otherwise extract from event
        val result = if (htmlContent != null && htmlContent.isNotBlank()) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "HtmlMessageText: Using provided htmlContent for event ${event.eventId}, length: ${htmlContent.length}, preview: ${htmlContent.take(
                        100,
                    )}",
                )
            }
            // Pass raw markup to the parser; entities are decoded at leaf text nodes.
            htmlContent
        } else {
            // Extract from event - prioritize sanitized_html over formatted_body
            val sanitized = extractSanitizedHtml(event)
            if (sanitized != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "HtmlMessageText: Using sanitized_html for event ${event.eventId}, length: ${sanitized.length}, preview: ${sanitized.take(
                            100,
                        )}",
                    )
                }
                sanitized
            } else {
                val formattedBody = event.decrypted?.optString("formatted_body")?.takeIf { it.isNotBlank() }
                    ?: event.content?.optString("formatted_body")?.takeIf { it.isNotBlank() }
                if (formattedBody != null) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "Andromuks",
                            "HtmlMessageText: Using formatted_body for event ${event.eventId}, length: ${formattedBody.length}, preview: ${formattedBody.take(
                                100,
                            )}",
                        )
                    }
                    // Pass raw markup to the parser; entities are decoded at leaf text nodes.
                    formattedBody
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "Andromuks",
                            "HtmlMessageText: No HTML content found for event ${event.eventId}",
                        )
                    }
                    null
                }
            }
        }
        result
    }

    // Memoized: this ran on every recomposition, doing a JSON optString plus decodeHtmlEntities
    // and (for replies) the stripReplyFallback regex, for a value that only depends on the event.
    val plainTextBody = remember(event, sanitizedHtml) {
        if (sanitizedHtml == null) {
            val content = event.content ?: event.decrypted
            val rawBody = content?.optString("body", "")?.let { decodeHtmlEntities(it) } ?: ""
            if (hasReplyFallback(event)) {
                stripReplyFallback(rawBody)
            } else {
                rawBody
            }
        } else {
            ""
        }
    }

    // Parse HTML
    val nodes = remember(sanitizedHtml) {
        if (sanitizedHtml == null) {
            emptyList()
        } else {
            try {
                // Cached across compositions, so scrolling a message off and back does not
                // re-parse it. remember() alone only covers the current composition.
                HtmlParser.parseCached(sanitizedHtml)
            } catch (e: Exception) {
                Log.e("Andromuks", "HtmlMessageText: Failed to parse HTML", e)
                emptyList()
            }
        }
    }

    // Separate table nodes for dedicated card rendering
    val tableNodes = remember(nodes) {
        nodes.filterIsInstance<HtmlNode.Tag>().filter { it.name == "table" }
    }
    // MSC2191: pull top-level *block* maths out of the text flow so they can be rendered
    // centered on their own line (like tables). Inline maths (and any maths nested deeper)
    // stay in the AnnotatedString as inline content.
    val blockMathLatex = remember(nodes) {
        nodes.filterIsInstance<HtmlNode.Tag>()
            .filter { it.name == "hicli-math" && !it.attributes["displaymode"].equals("inline", ignoreCase = true) }
            .map { extractMathLatex(it) }
            .filter { it.isNotBlank() }
    }
    val nonTableNodes = remember(nodes) {
        if (tableNodes.isEmpty() && blockMathLatex.isEmpty()) {
            nodes
        } else {
            nodes.filter {
                !(
                    it is HtmlNode.Tag && (
                        it.name == "table" ||
                            (it.name == "hicli-math" && !it.attributes["displaymode"].equals("inline", ignoreCase = true))
                        )
                    )
            }
        }
    }
    val tableDatas = remember(tableNodes) {
        tableNodes.map { parseTableNode(it) }
    }

    // OPPORTUNISTIC PROFILE LOADING: Extract Matrix user IDs from HTML and request profiles
    LaunchedEffect(nodes, event.roomId) {
        val vm = appViewModel
        if (vm != null && nodes.isNotEmpty()) {
            val userIds = extractMatrixUserIdsFromNodes(nodes)
            userIds.forEach { userId ->
                // Check if we already have the profile
                val existingProfile = vm.getUserProfile(userId, event.roomId)
                if (existingProfile == null) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "Andromuks",
                            "HtmlMessageText: Requesting profile on-demand for $userId from HTML",
                        )
                    }
                    vm.requestUserProfileOnDemand(userId, event.roomId)
                }
            }
        }
    }

    // Render to AnnotatedString with inline images
    val inlineImages = remember { mutableMapOf<String, InlineImageData>() }
    val inlineMatrixUsers = remember { mutableMapOf<String, InlineMatrixUserChip>() }
    val inlineMatrixRooms = remember { mutableMapOf<String, InlineMatrixRoomChip>() }
    val inlineCodeBlocks = remember {
        mutableMapOf<String, InlineCodeBlockPreview>()
    } // Map of code block IDs to preview data
    val spoilerStates = remember { mutableStateMapOf<String, Boolean>() }
    val spoilerContext = remember { SpoilerRenderContext(spoilerStates) }

    // Memoized: this tree walk allocates a SpanStyle per styled run and repopulates five maps,
    // and it used to run on EVERY recomposition of this composable — which, because
    // TimelineEventItem keys remembers on AppViewModel's timeline/receipt counters, means every
    // visible row re-walked on any edit or read receipt anywhere.
    //
    // derivedStateOf rather than a plain remember, because the walk reads `spoilerStates` (a
    // SnapshotStateMap) via spoilerContext.isRevealed. derivedStateOf tracks that read
    // automatically, so toggling a spoiler still re-renders — with a plain remember it would
    // freeze in whatever state it was first built. This only became possible once nextId()
    // stopped *writing* to that map (see SpoilerRenderContext.nextId).
    //
    // Keys are the walk's complete non-snapshot capture set: the node list and the text colour.
    // Verified by inspection — the builder body references nothing else from the enclosing scope
    // except spoilerContext and the four inline maps, all of which are themselves remembered and
    // therefore stable. Adding a capture without adding its key here renders stale content.
    //
    // NOTE this helps in-place recomposition only. Scrolling an item off and back builds a fresh
    // composition with empty remember slots; that case is covered by HtmlParser.parseCached.
    val renderedString by remember(sanitizedHtml, plainTextBody, nonTableNodes, color) {
        derivedStateOf {
            if (sanitizedHtml == null) {
                buildPlainTextAnnotatedStringWithCode(plainTextBody, LINK_STYLE, MONOSPACE_STYLE)
            } else {
                try {
                    spoilerContext.start()
                    inlineImages.clear()
                    inlineMatrixUsers.clear()
                    inlineMatrixRooms.clear()
                    inlineCodeBlocks.clear()
                    buildAnnotatedString {
                        var i = 0
                        var previousWasLineBreak = false
                        while (i < nonTableNodes.size) {
                            val node = nonTableNodes[i]

                            // Single spoiler span without reason
                            if (node is HtmlNode.Tag && node.name == "span") {
                                val classAttr = node.attributes["class"] ?: ""
                                if (classAttr.contains("hicli-spoiler") && node.children.isNotEmpty()) {
                                    appendSpoilerNodes(
                                        nodes = node.children,
                                        baseStyle = SpanStyle(color = color),
                                        inlineImages = inlineImages,
                                        inlineMatrixUsers = inlineMatrixUsers,
                                        inlineMatrixRooms = inlineMatrixRooms,
                                        spoilerContext = spoilerContext,
                                        reason = null,
                                        inlineCodeBlocks = inlineCodeBlocks,
                                    )
                                    previousWasLineBreak = false
                                    i++
                                    continue
                                }
                            }

                            // Reason + spoiler pattern
                            if (i + 1 < nonTableNodes.size) {
                                val spoilerData = extractSpoilerData(listOf(node, nonTableNodes[i + 1]))
                                if (spoilerData != null) {
                                    val (reason, contentNodes) = spoilerData
                                    if (contentNodes.isNotEmpty()) {
                                        appendSpoilerNodes(
                                            nodes = contentNodes,
                                            baseStyle = SpanStyle(color = color),
                                            inlineImages = inlineImages,
                                            inlineMatrixUsers = inlineMatrixUsers,
                                            inlineMatrixRooms = inlineMatrixRooms,
                                            spoilerContext = spoilerContext,
                                            reason = reason,
                                            inlineCodeBlocks = inlineCodeBlocks,
                                        )
                                        previousWasLineBreak = false
                                        i += 2
                                        continue
                                    }
                                }
                            }

                            // Track if previous node was a line break to trim leading whitespace from following text
                            val wasLineBreak = previousWasLineBreak
                            appendHtmlNode(
                                node = node,
                                baseStyle = SpanStyle(color = color),
                                inlineImages = inlineImages,
                                inlineMatrixUsers = inlineMatrixUsers,
                                inlineMatrixRooms = inlineMatrixRooms,
                                spoilerContext = spoilerContext,
                                previousWasLineBreak = wasLineBreak,
                                inlineCodeBlocks = inlineCodeBlocks,
                            )
                            previousWasLineBreak = node is HtmlNode.LineBreak

                            // Add an extra blank line between consecutive paragraphs (<p>/<div>),
                            // but do not insert it before other block elements (like <blockquote>).
                            if (node is HtmlNode.Tag && node.name in setOf("p", "div")) {
                                var j = i + 1
                                while (j < nonTableNodes.size) {
                                    val nextNode = nonTableNodes[j]
                                    if (nextNode is HtmlNode.Text && nextNode.content.trim().isEmpty()) {
                                        j++
                                        continue
                                    }
                                    break
                                }
                                val nextNonWhitespace = nonTableNodes.getOrNull(j)
                                if (nextNonWhitespace is HtmlNode.Tag && nextNonWhitespace.name in setOf("p", "div")) {
                                    append("\n")
                                }
                            }

                            i++
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Andromuks", "HtmlMessageText: Failed to render HTML", e)
                    AnnotatedString("")
                }
            }
        }
    }
    val annotatedString =
        if (sanitizedHtml != null && renderedString.text.endsWith("\n")) {
            // Avoid leaving trailing blank lines caused by block-level rendering (e.g. <p>).
            var end = renderedString.length
            while (end > 0 && renderedString.text[end - 1] == '\n') {
                end--
            }
            renderedString.subSequence(0, end)
        } else {
            renderedString
        }
    val density = LocalDensity.current
    val chipTextStyle = MaterialTheme.typography.labelLarge
    val bodyTextStyle = MaterialTheme.typography.bodyMedium
    val textMeasurer = rememberTextMeasurer()
    val primaryColor = MaterialTheme.colorScheme.primary

    // Calculate text line height to limit inline image height
    val textLineHeight = remember(textMeasurer, density) {
        val sampleLayout = textMeasurer.measure(text = AnnotatedString("Ag"))
        with(density) { sampleLayout.size.height.toDp().value.toInt() }
    }

    val roomChipColor = MaterialTheme.colorScheme.tertiary
    val roomChipTextColor = MaterialTheme.colorScheme.onTertiary

    // MSC2191 maths: colour equations to match surrounding text and follow the theme.
    val fallbackTextColor = MaterialTheme.colorScheme.onSurface
    val mathColor = if (color != Color.Unspecified) color else fallbackTextColor
    val mathColorArgb = mathColor.toArgb()
    // Inline maths render at the body text size so they sit naturally on the line.
    val mathTextSizePx = with(density) { bodyTextStyle.fontSize.toPx() }

    // CRITICAL FIX: Use inlineImages.size as a dependency to ensure recomputation when images are added/removed
    // This fixes the issue where inline images don't load after timeline orientation changes
    val inlineImagesSnapshot = remember(inlineImages.size, annotatedString) {
        inlineImages.toMap()
    }
    // Tapping an mxc-backed inline image (custom emoji / inline <img>) opens it in the
    // shared fullscreen ImageViewerDialog. Pure unicode emoji (e.g. 👍) are plain text and
    // never reach this path, so they stay inert. The handler is remembered so it stays a
    // stable key for the inlineContentMap below.
    var inlineImageViewer by remember { mutableStateOf<InlineImageData?>(null) }
    val handleInlineImageClick = remember(onInlineImageClick) {
        { data: InlineImageData ->
            onInlineImageClick(data)
            inlineImageViewer = data
        }
    }

    // Fullscreen viewer for a tapped inline image. Defined as a local composable so it can be
    // hosted in every render branch below (the table/block-math branch returns early).
    @Composable
    fun InlineImageViewerHost() {
        inlineImageViewer?.let { data ->
            val media = remember(data) { inlineImageToMediaMessage(data) }
            if (media != null) {
                ImageViewerDialog(
                    mediaMessage = media.first,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    isEncrypted = media.second,
                    onDismiss = { inlineImageViewer = null },
                )
            } else {
                // Unconvertible src (shouldn't happen for mxc-backed images) — clear the request.
                LaunchedEffect(data) { inlineImageViewer = null }
            }
        }
    }
    val inlineContentMap =
        remember(annotatedString, inlineImagesSnapshot, inlineMatrixUsers.toMap(), inlineMatrixRooms.toMap(), inlineCodeBlocks.toMap(), onMatrixUserClick, onRoomLinkClick, onCodeBlockClick, handleInlineImageClick, density, chipTextStyle, textMeasurer, textLineHeight, primaryColor, isEmojiOnly, color, bodyTextStyle, roomChipColor, roomChipTextColor, homeserverUrl, authToken, mathColorArgb, mathTextSizePx, inlineImageSizing) {
            val map = mutableMapOf<String, InlineTextContent>()
            inlineImagesSnapshot.forEach { (id, imageData) ->
                // MSC2191 maths: render the LaTeX to a JLaTeXMath drawable instead of a network image.
                val latex = imageData.latex
                if (latex != null) {
                    val drawable = runCatching {
                        JLatexMathDrawable.builder(latex)
                            .textSize(mathTextSizePx)
                            .color(mathColorArgb)
                            .align(JLatexMathDrawable.ALIGN_LEFT)
                            .build()
                    }.getOrNull()
                    if (drawable != null) {
                        val widthSp = with(density) { drawable.intrinsicWidth.toDp().value.sp }
                        val heightSp = with(density) { drawable.intrinsicHeight.toDp().value.sp }
                        map[id] = InlineTextContent(
                            Placeholder(
                                width = widthSp,
                                height = heightSp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            LatexDrawableImage(drawable = drawable, modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        // Malformed LaTeX: fall back to monospace source so nothing blanks out.
                        val fallbackStyle = bodyTextStyle.copy(fontFamily = FontFamily.Monospace, color = mathColor)
                        val textLayout = textMeasurer.measure(text = AnnotatedString(latex), style = fallbackStyle)
                        map[id] = InlineTextContent(
                            Placeholder(
                                width = with(density) { textLayout.size.width.toDp().value.sp },
                                height = with(density) { textLayout.size.height.toDp().value.sp },
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            Text(text = latex, style = fallbackStyle)
                        }
                    }
                    return@forEach
                }
                // Limit image height to text line height, but enlarge it for emoji-only messages.
                val baseMaxHeight = minOf(imageData.height, textLineHeight)
                val maxHeight = if (isEmojiOnly) {
                    baseMaxHeight * EMOJI_ONLY_FONT_SCALE
                } else {
                    baseMaxHeight
                }
                // A full-size view (the expanded bio) lets images that declared a size use it
                // instead; anything that declared none keeps the inline size computed above.
                val (imageWidth, imageHeight) = if (inlineImageSizing != null) {
                    inlineImageSizeSp(imageData, inlineImageSizing, fallbackHeightSp = maxHeight.toFloat())
                } else {
                    maxHeight.toFloat() to maxHeight.toFloat()
                }
                // TextCenter anchors the placeholder to the surrounding text's ascent/descent and
                // leaves the line box alone, which is what keeps an emoticon sitting on the line
                // instead of pushing it apart. A placeholder taller than the line then overflows
                // it and the next line draws straight through the image — so anything bigger than
                // one line aligns to the line box instead, which grows to fit it.
                val verticalAlign = if (imageHeight > textLineHeight) {
                    PlaceholderVerticalAlign.Center
                } else {
                    PlaceholderVerticalAlign.TextCenter
                }
                map[id] = InlineTextContent(
                    Placeholder(
                        width = imageWidth.sp,
                        height = imageHeight.sp,
                        placeholderVerticalAlign = verticalAlign,
                    ),
                ) {
                    InlineImage(
                        src = imageData.src,
                        alt = imageData.alt,
                        width = imageWidth,
                        height = imageHeight,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isHidden = imageData.isHidden,
                        onClick = { handleInlineImageClick(imageData) },
                    )
                }
            }
            inlineMatrixUsers.forEach { (id, chip) ->
                val textLayout = textMeasurer.measure(
                    text = AnnotatedString(chip.displayText),
                    style = chipTextStyle.copy(color = primaryColor),
                )
                val textWidthDp = with(density) { textLayout.size.width.toDp() }
                val widthSp = with(density) { textWidthDp.value.sp }
                val heightSp = with(density) { textLayout.size.height.toDp().value.sp }
                map[id] = InlineTextContent(
                    Placeholder(
                        width = widthSp,
                        height = heightSp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Text(
                        text = chip.displayText,
                        style = chipTextStyle,
                        color = primaryColor,
                        modifier = Modifier.clickable { onMatrixUserClick(chip.userId) },
                    )
                }
            }
            inlineMatrixRooms.forEach { (id, chip) ->
                // Measure text with padding included to get accurate size
                val horizontalPadding = 4.dp
                val verticalPadding = 0.dp // No vertical padding to keep text aligned with baseline
                val textLayout = textMeasurer.measure(
                    text = AnnotatedString(chip.displayText),
                    style = chipTextStyle.copy(color = roomChipTextColor),
                    constraints = Constraints(maxWidth = Int.MAX_VALUE),
                )
                val textWidthDp = with(density) { textLayout.size.width.toDp() }
                val textHeightDp = with(density) { textLayout.size.height.toDp() }
                // Add padding to dimensions, with a small extra margin to prevent clipping
                val widthSp = with(density) { (textWidthDp + horizontalPadding * 2 + 2.dp).value.sp }
                val heightSp = with(density) { textHeightDp.value.sp } // No vertical padding, so use text height directly
                map[id] = InlineTextContent(
                    Placeholder(
                        width = widthSp,
                        height = heightSp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Surface(
                        color = roomChipColor,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable { onRoomLinkClick(chip.roomLink) },
                    ) {
                        Text(
                            text = chip.displayText,
                            style = chipTextStyle,
                            color = roomChipTextColor,
                            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
                        )
                    }
                }
            }
            // Code blocks are now rendered directly in the text flow, not as inline content
            // The inlineCodeBlocks map is still used to store full code for click handling
            map
        }

    // Table dialog open/close state — one entry per table in the message
    val tableDialogStates = remember(tableNodes.size) {
        mutableStateListOf<Boolean>().apply {
            repeat(tableNodes.size) { add(false) }
        }
    }

    // Block ("display") maths are rendered slightly larger than the body text.
    val blockMathTextSizePx = mathTextSizePx * 1.3f

    if (tableNodes.isNotEmpty() || blockMathLatex.isNotEmpty()) {
        // Message contains HTML tables and/or block maths: render text (if any) + tables + centered equations
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Column(modifier = modifier) {
            if (annotatedString.text.isNotBlank()) {
                Text(
                    text = annotatedString,
                    modifier = Modifier.pointerInput(annotatedString) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downTime = System.currentTimeMillis()
                            val downPosition = down.position
                            var up: PointerInputChange? = null
                            var wasMoved = false
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.pressed) {
                                        val xDiff = kotlin.math.abs(change.position.x - downPosition.x)
                                        val yDiff = kotlin.math.abs(change.position.y - downPosition.y)
                                        if (xDiff > 10 || yDiff > 10) wasMoved = true
                                    } else {
                                        up = change
                                    }
                                }
                            } while (up == null && event.changes.any { it.pressed })
                            val isTap = !wasMoved && (System.currentTimeMillis() - downTime) < 500
                            if (isTap && up != null) {
                                textLayoutResult?.let { layoutResult ->
                                    val offset = layoutResult.getOffsetForPosition(downPosition)
                                    val spoilerAnnotation = annotatedString.getStringAnnotations(
                                        tag = "SPOILER",
                                        start = offset,
                                        end = offset,
                                    ).firstOrNull()
                                    if (spoilerAnnotation != null) {
                                        up.consume()
                                        spoilerContext.toggle(
                                            spoilerAnnotation.item,
                                        )
                                        return@awaitEachGesture
                                    }
                                    val codeBlockAnnotation = annotatedString.getStringAnnotations(
                                        tag = "CODE_BLOCK",
                                        start = offset,
                                        end = offset,
                                    ).firstOrNull()
                                    if (codeBlockAnnotation !=
                                        null
                                    ) {
                                        up.consume()
                                        val cb = inlineCodeBlocks[codeBlockAnnotation.item]
                                        if (cb !=
                                            null
                                        ) {
                                            onCodeBlockClick(
                                                cb.fullCode,
                                            )
                                        }
                                        return@awaitEachGesture
                                    }
                                    val hasInteractive = annotatedString.getStringAnnotations(
                                        tag = "MATRIX_USER",
                                        start = offset,
                                        end = offset,
                                    ).isNotEmpty() ||
                                        annotatedString.getStringAnnotations(
                                            tag = "ROOM_LINK",
                                            start = offset,
                                            end = offset,
                                        ).isNotEmpty() ||
                                        annotatedString.getStringAnnotations(
                                            tag = "URL",
                                            start = offset,
                                            end = offset,
                                        ).isNotEmpty()
                                    if (hasInteractive) {
                                        up.consume()
                                        annotatedString.getStringAnnotations(
                                            tag = "MATRIX_USER",
                                            start = offset,
                                            end = offset,
                                        ).firstOrNull()?.let {
                                            onMatrixUserClick(it.item)
                                            return@awaitEachGesture
                                        }
                                        annotatedString.getStringAnnotations(
                                            tag = "ROOM_LINK",
                                            start = offset,
                                            end = offset,
                                        ).firstOrNull()?.let {
                                            val rl = extractRoomLink(
                                                it.item,
                                            )
                                            if (rl != null) {
                                                onRoomLinkClick(rl)
                                                return@awaitEachGesture
                                            }
                                        }
                                        annotatedString.getStringAnnotations(
                                            tag = "URL",
                                            start = offset,
                                            end = offset,
                                        ).firstOrNull()?.let { annotation ->
                                            val url = annotation.item
                                            when {
                                                url.startsWith(
                                                    "matrix:u/",
                                                ) -> {
                                                    val uid = url.removePrefix(
                                                        "matrix:u/",
                                                    ).let {
                                                        if (it.startsWith(
                                                                "@",
                                                            )
                                                        ) {
                                                            it
                                                        } else {
                                                            "@$it"
                                                        }
                                                    }
                                                    onMatrixUserClick(uid)
                                                }

                                                url.startsWith(
                                                    "https://matrix.to/#/",
                                                ) -> {
                                                    val dec = runCatching {
                                                        URLDecoder.decode(
                                                            url.removePrefix("https://matrix.to/#/"),
                                                            Charsets.UTF_8.name(),
                                                        )
                                                    }.getOrDefault(
                                                        url.removePrefix("https://matrix.to/#/"),
                                                    )
                                                    if (dec.startsWith(
                                                            "@",
                                                        )
                                                    ) {
                                                        onMatrixUserClick(
                                                            dec,
                                                        )
                                                    } else {
                                                        val rl = extractRoomLink(
                                                            url,
                                                        )
                                                        if (rl != null) {
                                                            onRoomLinkClick(
                                                                rl,
                                                            )
                                                        } else {
                                                            try {
                                                                context.startActivity(
                                                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                                                )
                                                            } catch (_: Exception) {}
                                                        }
                                                    }
                                                }

                                                url.startsWith(
                                                    "matrix:roomid/",
                                                ) || url.startsWith(
                                                    "matrix:/roomid/",
                                                ) || url.startsWith(
                                                    "matrix:r/",
                                                ) || url.startsWith(
                                                    "matrix:/r/",
                                                ) -> {
                                                    val rl = extractRoomLink(
                                                        url,
                                                    )
                                                    if (rl != null) {
                                                        onRoomLinkClick(
                                                            rl,
                                                        )
                                                    } else {
                                                        try {
                                                            context.startActivity(
                                                                Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                                            )
                                                        } catch (_: Exception) {}
                                                    }
                                                }

                                                else -> try {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                                    )
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    style = if (isEmojiOnly) {
                        MaterialTheme.typography.bodyMedium.copy(
                            color = color,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * EMOJI_ONLY_FONT_SCALE,
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium.copy(color = color)
                    },
                    inlineContent = inlineContentMap,
                    onTextLayout = { textLayoutResult = it },
                )
                Spacer(Modifier.height(4.dp))
            }
            tableDatas.forEachIndexed { idx, tableData ->
                if (idx > 0) Spacer(Modifier.height(4.dp))
                HtmlTablePreviewCard(
                    tableData = tableData,
                    onClick = { tableDialogStates[idx] = true },
                )
                if (tableDialogStates[idx]) {
                    HtmlTableDialog(
                        tableData = tableData,
                        onDismiss = { tableDialogStates[idx] = false },
                    )
                }
            }
            blockMathLatex.forEachIndexed { idx, latex ->
                if (idx > 0 || tableDatas.isNotEmpty() || annotatedString.text.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                }
                BlockMath(
                    latex = latex,
                    colorArgb = mathColorArgb,
                    textSizePx = blockMathTextSizePx,
                )
            }
        }
        InlineImageViewerHost()
        return
    }

    if (annotatedString.text.isEmpty()) {
        // Fallback if rendering failed
        val content = event.content ?: event.decrypted
        val body = content?.optString("body", "")?.let { decodeHtmlEntities(it) } ?: ""
        val baseStyle = MaterialTheme.typography.bodyMedium
        val textStyle = if (isEmojiOnly) {
            baseStyle.copy(fontSize = baseStyle.fontSize * EMOJI_ONLY_FONT_SCALE)
        } else {
            baseStyle
        }
        Text(
            text = body,
            style = textStyle,
            modifier = modifier,
            color = color,
        )
    } else {
        // Use Text with custom gesture handling that only consumes taps on interactive elements
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

        Text(
            text = annotatedString,
            modifier = modifier.pointerInput(annotatedString) {
                awaitEachGesture {
                    // Wait for a down event (finger touches screen)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val downPosition = down.position

                    // Wait for all pointer changes until release
                    var up: PointerInputChange? = null
                    var wasMoved = false

                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                // Check if finger moved significantly (more than touch slop)
                                val xDiff = kotlin.math.abs(change.position.x - downPosition.x)
                                val yDiff = kotlin.math.abs(change.position.y - downPosition.y)
                                if (xDiff > 10 || yDiff > 10) {
                                    wasMoved = true
                                }
                            } else {
                                // Finger lifted
                                up = change
                            }
                        }
                    } while (up == null && event.changes.any { it.pressed })

                    // Check if this was a tap (short duration, no movement)
                    val upTime = System.currentTimeMillis()
                    val duration = upTime - downTime
                    val isTap = !wasMoved && duration < 500 // Less than 500ms = tap, not long press

                    if (isTap && up != null) {
                        // This is a tap, check if it's on an interactive element
                        textLayoutResult?.let { layoutResult ->
                            val offset = layoutResult.getOffsetForPosition(downPosition)

                            // Check if tap is on a Matrix user pill, room link, or URL
                            val spoilerAnnotation = annotatedString.getStringAnnotations(
                                tag = "SPOILER",
                                start = offset,
                                end = offset,
                            ).firstOrNull()
                            if (spoilerAnnotation != null) {
                                up.consume()
                                spoilerContext.toggle(spoilerAnnotation.item)
                                return@awaitEachGesture
                            }

                            // Check for code block annotation first
                            val codeBlockAnnotation = annotatedString.getStringAnnotations(
                                tag = "CODE_BLOCK",
                                start = offset,
                                end = offset,
                            ).firstOrNull()
                            if (codeBlockAnnotation != null) {
                                up.consume()
                                val codeBlockId = codeBlockAnnotation.item
                                val codeBlock = inlineCodeBlocks[codeBlockId]
                                if (codeBlock != null) {
                                    onCodeBlockClick(codeBlock.fullCode)
                                }
                                return@awaitEachGesture
                            }

                            val hasMatrixUser = annotatedString.getStringAnnotations(
                                tag = "MATRIX_USER",
                                start = offset,
                                end = offset,
                            ).isNotEmpty()
                            val hasRoomLink = annotatedString.getStringAnnotations(
                                tag = "ROOM_LINK",
                                start = offset,
                                end = offset,
                            ).isNotEmpty()
                            val hasUrl = annotatedString.getStringAnnotations(
                                tag = "URL",
                                start = offset,
                                end = offset,
                            ).isNotEmpty()

                            if (hasMatrixUser || hasRoomLink || hasUrl) {
                                // Consume the event since we're handling it
                                up.consume()

                                // Matrix user annotations take precedence
                                annotatedString.getStringAnnotations(tag = "MATRIX_USER", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        onMatrixUserClick(annotation.item)
                                        return@awaitEachGesture
                                    }

                                // Matrix room link annotations
                                annotatedString.getStringAnnotations(tag = "ROOM_LINK", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        val roomLink = extractRoomLink(annotation.item)
                                        if (roomLink != null) {
                                            if (BuildConfig.DEBUG) {
                                                Log.d(
                                                    "Andromuks",
                                                    "HtmlMessageText: room link tapped for ${roomLink.roomIdOrAlias}",
                                                )
                                            }
                                            onRoomLinkClick(roomLink)
                                            return@awaitEachGesture
                                        }
                                    }

                                // Check if the tapped position has a URL annotation
                                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        val url = annotation.item
                                        when {
                                            url.startsWith("matrix:u/") -> {
                                                val rawId = url.removePrefix("matrix:u/")
                                                val userId = if (rawId.startsWith("@")) rawId else "@$rawId"
                                                if (BuildConfig.DEBUG) {
                                                    Log.d(
                                                        "Andromuks",
                                                        "HtmlMessageText: matrix:u link tapped for $userId",
                                                    )
                                                }
                                                onMatrixUserClick(userId)
                                            }

                                            url.startsWith("https://matrix.to/#/") -> {
                                                val encodedPart = url.removePrefix("https://matrix.to/#/")
                                                val decoded = runCatching {
                                                    URLDecoder.decode(
                                                        encodedPart,
                                                        Charsets.UTF_8.name(),
                                                    )
                                                }
                                                    .getOrDefault(encodedPart)
                                                if (decoded.startsWith("@")) {
                                                    // User link
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "Andromuks",
                                                            "HtmlMessageText: matrix.to link tapped for $decoded",
                                                        )
                                                    }
                                                    onMatrixUserClick(decoded)
                                                } else {
                                                    // Check if it's a room link (starts with ! or #)
                                                    val roomLink = extractRoomLink(url)
                                                    if (roomLink != null) {
                                                        if (BuildConfig.DEBUG) {
                                                            Log.d(
                                                                "Andromuks",
                                                                "HtmlMessageText: matrix.to room link tapped for ${roomLink.roomIdOrAlias}",
                                                            )
                                                        }
                                                        onRoomLinkClick(roomLink)
                                                    } else {
                                                        // Fallback to opening in browser for unrecognized matrix.to links
                                                        try {
                                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                            context.startActivity(intent)
                                                            if (BuildConfig.DEBUG) {
                                                                Log.d(
                                                                    "Andromuks",
                                                                    "Opening URL: $url",
                                                                )
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("Andromuks", "Failed to open URL: $url", e)
                                                        }
                                                    }
                                                }
                                            }

                                            url.startsWith(
                                                "matrix:roomid/",
                                            ) || url.startsWith(
                                                "matrix:/roomid/",
                                            ) || url.startsWith("matrix:r/") || url.startsWith("matrix:/r/") -> {
                                                // Matrix room link in matrix: URI format
                                                val roomLink = extractRoomLink(url)
                                                if (roomLink != null) {
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            "Andromuks",
                                                            "HtmlMessageText: matrix: room link tapped for ${roomLink.roomIdOrAlias}",
                                                        )
                                                    }
                                                    onRoomLinkClick(roomLink)
                                                } else {
                                                    // Fallback to opening in browser
                                                    try {
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                        context.startActivity(intent)
                                                        if (BuildConfig.DEBUG) Log.d("Andromuks", "Opening URL: $url")
                                                    } catch (e: Exception) {
                                                        Log.e("Andromuks", "Failed to open URL: $url", e)
                                                    }
                                                }
                                            }

                                            else -> {
                                                // Open URL in browser
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    context.startActivity(intent)
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "Opening URL: $url")
                                                } catch (e: Exception) {
                                                    Log.e("Andromuks", "Failed to open URL: $url", e)
                                                }
                                            }
                                        }
                                    }
                            }
                            // If not on an interactive element, don't consume - let parent handle it
                        }
                    }
                    // If it's a long press or other gesture, don't consume - let parent handle it
                }
            },
            style = if (isEmojiOnly) {
                MaterialTheme.typography.bodyMedium.copy(
                    color = color,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * EMOJI_ONLY_FONT_SCALE,
                )
            } else {
                MaterialTheme.typography.bodyMedium.copy(color = color)
            },
            inlineContent = inlineContentMap,
            onTextLayout = { layoutResult ->
                textLayoutResult = layoutResult
            },
        )
    }
    InlineImageViewerHost()
}

/**
 * Convert an mxc-backed [InlineImageData] into a [MediaMessage] for [ImageViewerDialog].
 * Returns the message paired with whether the media is encrypted, or null if the src can't
 * be resolved to an mxc:// URL. `src` may be a raw `mxc://server/id` or a gomuks
 * `_gomuks/media/server/id?encrypted=…` path; the query carries the encryption flag.
 */
private fun inlineImageToMediaMessage(data: InlineImageData): Pair<MediaMessage, Boolean>? {
    val raw = data.src
    val encrypted = raw.contains("encrypted=true")
    val mxc = when {
        raw.startsWith("mxc://") -> raw.substringBefore("?")

        raw.startsWith("_gomuks/media/") -> {
            val parts = raw.removePrefix("_gomuks/media/").substringBefore("?").split("/", limit = 2)
            if (parts.size == 2) "mxc://${parts[0]}/${parts[1]}" else return null
        }

        else -> return null
    }
    val media = MediaMessage(
        url = mxc,
        filename = data.alt.ifBlank { mxc.substringAfterLast("/") },
        caption = null,
        info = MediaInfo(width = 0, height = 0, size = 0L, mimeType = "image/png", blurHash = null),
        msgType = "m.image",
    )
    return media to encrypted
}

/**
 * Fit a block image's declared size inside the space available, preserving its aspect ratio and
 * never upscaling past what the markup asked for.
 *
 * Kept in Dp throughout: the caller measures its own container, so there is no reason to convert
 * through sp the way inline placeholders must.
 */
internal fun blockImageSize(image: HtmlSegment.BlockImage, maxWidth: Dp, maxHeight: Dp): Pair<Dp, Dp> {
    val aspect = image.width.toFloat() / image.height.toFloat()
    var width = minOf(image.width.dp, maxWidth)
    var height = width / aspect
    if (height > maxHeight) {
        height = maxHeight
        width = height * aspect
    }
    return width to height
}

/**
 * A real image from HTML, laid out on its own instead of as inline text content.
 *
 * [width] and [height] are the final drawn size — the caller knows its own container width, which
 * inline content never does, and that is the whole reason this exists (see
 * [splitTopLevelBlockImages]). Tapping opens the same fullscreen viewer an inline image does.
 */
@Composable
internal fun BlockHtmlImage(image: HtmlSegment.BlockImage, width: Dp, height: Dp, homeserverUrl: String, authToken: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    var viewerRequest by remember { mutableStateOf<InlineImageData?>(null) }

    val imageUrl = remember(image.src, homeserverUrl) {
        when {
            image.src.startsWith("mxc://") -> MediaUtils.mxcToHttpUrl(image.src, homeserverUrl)
            image.src.startsWith("_gomuks/media/") -> "${homeserverUrl.trimEnd('/')}/${image.src}"
            else -> null
        }
    }

    if (imageUrl == null) {
        Log.w("Andromuks", "BlockHtmlImage: unresolvable src=${image.src}, falling back to alt text")
        Text(text = image.alt, style = MaterialTheme.typography.bodyMedium)
        return
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build(),
        imageLoader = imageLoader,
        contentDescription = image.alt,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(width = width, height = height)
            .clickable { viewerRequest = InlineImageData(src = image.src, alt = image.alt, height = image.height) },
        onError = { errorState ->
            CacheUtils.handleImageLoadError(
                imageUrl = imageUrl,
                throwable = errorState.result.throwable,
                imageLoader = imageLoader,
                context = "BlockHtmlImage",
            )
        },
    )

    viewerRequest?.let { data ->
        val media = remember(data) { inlineImageToMediaMessage(data) }
        if (media != null) {
            ImageViewerDialog(
                mediaMessage = media.first,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = media.second,
                onDismiss = { viewerRequest = null },
            )
        } else {
            LaunchedEffect(data) { viewerRequest = null }
        }
    }
}

/**
 * Draws an already-built JLaTeXMath drawable, scaled to fill the given box while preserving
 * the drawable's aspect ratio. Used both for inline maths (box sized to the drawable's
 * intrinsics) and block maths (box sized for display).
 */
@Composable
private fun LatexDrawableImage(drawable: android.graphics.drawable.Drawable, modifier: Modifier = Modifier) {
    val intrinsicWidth = drawable.intrinsicWidth.coerceAtLeast(1)
    val intrinsicHeight = drawable.intrinsicHeight.coerceAtLeast(1)
    Canvas(modifier = modifier) {
        val scale = minOf(size.width / intrinsicWidth, size.height / intrinsicHeight)
        val drawW = intrinsicWidth * scale
        val drawH = intrinsicHeight * scale
        val left = ((size.width - drawW) / 2f)
        val top = ((size.height - drawH) / 2f)
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val save = nc.save()
            nc.translate(left, top)
            nc.scale(scale, scale)
            drawable.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            drawable.draw(nc)
            nc.restoreToCount(save)
        }
    }
}

/**
 * MSC2191 block ("display") maths: render the equation centered on its own line, with
 * horizontal scrolling so very wide formulae (e.g. the quadratic formula) aren't clipped.
 */
@Composable
private fun BlockMath(latex: String, colorArgb: Int, textSizePx: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val drawable = remember(latex, textSizePx, colorArgb) {
        runCatching {
            JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .color(colorArgb)
                .align(JLatexMathDrawable.ALIGN_CENTER)
                .build()
        }.getOrNull()
    }
    if (drawable == null) {
        // Malformed LaTeX: show the raw source rather than nothing.
        Text(
            text = latex,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = Color(colorArgb),
            ),
            modifier = modifier,
        )
        return
    }
    val widthDp = with(density) { drawable.intrinsicWidth.toDp() }
    val heightDp = with(density) { drawable.intrinsicHeight.toDp() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.Center,
    ) {
        LatexDrawableImage(
            drawable = drawable,
            modifier = Modifier.size(width = widthDp, height = heightDp),
        )
    }
}

/**
 * Composable for rendering inline images in HTML content
 */
@Composable
private fun InlineImage(
    src: String,
    alt: String,
    width: Float,
    height: Float,
    homeserverUrl: String,
    authToken: String,
    isHidden: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Use shared ImageLoader singleton with custom User-Agent
    val imageLoader = remember { ImageLoaderSingleton.get(context) }

    // Determine the MXC URL for caching
    val mxcUrl = remember(src) {
        when {
            src.startsWith("mxc://") -> src

            src.startsWith("_gomuks/media/") -> {
                // Convert _gomuks/media/server/mediaId back to mxc://server/mediaId
                val parts = src.removePrefix("_gomuks/media/").split("?")[0].split("/", limit = 2)
                if (parts.size == 2) "mxc://${parts[0]}/${parts[1]}" else null
            }

            else -> null
        }
    }

    // Check if we have a cached version first
    // CRITICAL FIX: Use Dispatchers.IO for file I/O operations (file.exists() is blocking)
    // IntelligentMediaCache.getCachedFile() performs file.exists() which blocks the thread
    var cachedFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(mxcUrl) {
        cachedFile = mxcUrl?.let {
            withContext(Dispatchers.IO) {
                IntelligentMediaCache.getCachedFile(context, it)
            }
        }
    }

    // Convert to HTTP URL or use cached file
    val imageUrl = remember(src, homeserverUrl, cachedFile) {
        val file = cachedFile
        if (file != null) {
            // Use cached file
            if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineImage: Using cached file: ${file.absolutePath}")
            file.absolutePath
        } else {
            // Use HTTP URL
            val url = when {
                src.startsWith("mxc://") -> {
                    MediaUtils.mxcToHttpUrl(src, homeserverUrl)
                }

                src.startsWith("_gomuks/media/") -> {
                    // Already in gomuks format, just prepend homeserver URL
                    // Ensure proper URL construction (handle trailing slash in homeserverUrl)
                    val baseUrl = if (homeserverUrl.endsWith("/")) {
                        homeserverUrl.dropLast(1)
                    } else {
                        homeserverUrl
                    }
                    "$baseUrl/$src"
                }

                else -> {
                    Log.w("Andromuks", "InlineImage: Invalid image source: $src")
                    null
                }
            }
            if (BuildConfig.DEBUG && url != null) {
                Log.d("Andromuks", "InlineImage: Converted src=$src to url=$url (homeserverUrl=$homeserverUrl)")
            }
            url
        }
    }

    // NOTE: Coil handles caching automatically with memoryCachePolicy and diskCachePolicy
    // No need to manually download - would cause duplicate requests (Coil + okhttp)

    if (isHidden) {
        // Render a placeholder with the same size to avoid layout changes.
        Box(
            modifier = Modifier
                .size(width.dp, height.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
        )
    } else if (imageUrl != null) {
        // CRITICAL FIX: Disable error caching for inline images to allow retries
        // Inline images (custom emojis) are small and should retry on failure
        // This fixes the issue where images fail once and never load again
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    if (cachedFile == null) {
                    }
                }
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            imageLoader = imageLoader,
            contentDescription = alt,
            modifier = Modifier
                .size(width.dp, height.dp)
                .let { if (onClick != null) it.clickable { onClick() } else it },
            onError = { errorState ->
                // CRITICAL FIX: Use existing error handling utility for consistent error handling
                // This logs the error and invalidates cache if appropriate
                // Cache invalidation allows retries on next render instead of permanent error caching
                CacheUtils.handleImageLoadError(
                    imageUrl = imageUrl,
                    throwable = errorState.result.throwable,
                    imageLoader = imageLoader,
                    context = "InlineImage",
                )
            },
        )
    } else {
        // Fallback to alt text
        Text(text = alt, fontSize = (height * 0.6f).sp)
    }
}

private fun AnnotatedString.Builder.endsWithWhitespace(): Boolean {
    if (length == 0) return false
    return toString().last().isWhitespace()
}

/**
 * Convert HTML message to styled SpannedString for Android notifications
 * This provides basic HTML formatting support in notification text
 */
fun htmlToNotificationText(htmlContent: String): android.text.Spanned {
    return try {
        // Parse raw markup; the parser decodes entities at leaf text nodes.
        val nodes = HtmlParser.parse(htmlContent)

        // Convert to SpannableStringBuilder with styles
        val builder = android.text.SpannableStringBuilder()

        fun appendNodeToSpannable(node: HtmlNode) {
            when (node) {
                is HtmlNode.Text -> {
                    // Collapse HTML source whitespace (indentation newlines/tabs and runs of
                    // spaces between tags) into a single space — real line breaks come from the
                    // block-level tags handled below (p/div/ul/li/br/etc.). Without this the raw
                    // pretty-printed markup leaks its indentation into the notification text.
                    val collapsed = node.content.replace(Regex("\\s+"), " ")
                    if (collapsed.isEmpty()) return
                    val prevEndsWithSpaceOrBreak = builder.isEmpty() ||
                        builder[builder.length - 1].let { it == ' ' || it == '\n' }
                    val toAppend = if (collapsed.startsWith(" ") && prevEndsWithSpaceOrBreak) {
                        collapsed.trimStart()
                    } else {
                        collapsed
                    }
                    if (toAppend.isNotEmpty()) builder.append(toAppend)
                }

                is HtmlNode.LineBreak -> builder.append("\n")

                is HtmlNode.Tag -> {
                    val startIndex = builder.length

                    when (node.name) {
                        "strong", "b" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }

                        "em", "i" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }

                        "u" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.UnderlineSpan(),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }

                        "s", "del" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.StrikethroughSpan(),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }

                        "code" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.TypefaceSpan("monospace"),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }

                        "pre" -> {
                            if (builder.isNotEmpty() && !builder.toString().endsWith("\n")) builder.append("\n")
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.TypefaceSpan("monospace"),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            builder.append("\n")
                        }

                        "br" -> builder.append("\n")

                        "hr" -> {
                            if (builder.isNotEmpty() && !builder.toString().endsWith("\n")) builder.append("\n")
                            builder.append("────────\n")
                        }

                        "mx-reply" -> {
                            // Skip rich reply fallback block
                        }

                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            if (builder.isNotEmpty() && !builder.toString().endsWith("\n")) builder.append("\n")
                            node.children.forEach { appendNodeToSpannable(it) }
                            // Make headers bold
                            builder.setSpan(
                                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            builder.append("\n")
                        }

                        "p", "div" -> {
                            if (builder.isNotEmpty() && !builder.toString().endsWith("\n")) builder.append("\n")
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.append("\n")
                        }

                        "blockquote" -> {
                            if (builder.isNotEmpty() && !builder.toString().endsWith("\n")) builder.append("\n")
                            builder.append("│ ")
                            val quoteStart = builder.length
                            node.children.forEach { child ->
                                // Skip adding extra newlines from nested <p> tags in blockquotes
                                if (child is HtmlNode.Tag && child.name in setOf("p", "div")) {
                                    child.children.forEach { appendNodeToSpannable(it) }
                                } else {
                                    appendNodeToSpannable(child)
                                }
                            }
                            // Make the quoted text italic
                            builder.setSpan(
                                android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                                quoteStart,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            builder.append("\n")
                        }

                        "ul" -> {
                            builder.append("\n")
                            node.children.forEach { child ->
                                if (child is HtmlNode.Tag && child.name == "li") {
                                    builder.append("• ")
                                    child.children.forEach { appendNodeToSpannable(it) }
                                    builder.append("\n")
                                }
                            }
                        }

                        "ol" -> {
                            builder.append("\n")
                            var index = 1
                            node.children.forEach { child ->
                                if (child is HtmlNode.Tag && child.name == "li") {
                                    builder.append("$index. ")
                                    child.children.forEach { appendNodeToSpannable(it) }
                                    builder.append("\n")
                                    index++
                                }
                            }
                        }

                        "a" -> {
                            val href = node.attributes["href"] ?: ""
                            node.children.forEach { appendNodeToSpannable(it) }
                            if (href.isNotEmpty()) {
                                builder.setSpan(
                                    android.text.style.URLSpan(href),
                                    startIndex,
                                    builder.length,
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                            }
                        }

                        "img" -> {
                            // For images in notifications, show alt text or a placeholder
                            val alt = node.attributes["alt"] ?: node.attributes["title"] ?: "[Image]"
                            builder.append(alt)
                        }

                        else -> {
                            // For unsupported tags, just append children
                            node.children.forEach { appendNodeToSpannable(it) }
                        }
                    }
                }
            }
        }

        nodes.forEach { appendNodeToSpannable(it) }

        // Clean up multiple consecutive newlines
        var result = builder.toString()
        while (result.contains("\n\n\n")) {
            result = result.replace("\n\n\n", "\n\n")
        }
        result = result.trim()

        // Return as SpannedString with preserved spans
        android.text.SpannableString(result).also { spanned ->
            builder.getSpans(0, builder.length, Any::class.java).forEach { span ->
                val spanStart = builder.getSpanStart(span)
                val spanEnd = builder.getSpanEnd(span)
                val spanFlags = builder.getSpanFlags(span)
                if (spanStart < result.length && spanEnd <= result.length) {
                    spanned.setSpan(span, spanStart, spanEnd, spanFlags)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Andromuks", "htmlToNotificationText: Error converting HTML to notification text", e)
        // Fallback to plain text
        android.text.SpannableString(htmlContent)
    }
}

/**
 * Render HTML content to AnnotatedString for simple display (profile bios, etc.)
 * This is a simplified version that doesn't handle inline images from network,
 * Matrix user chips, or other complex features - just basic text formatting.
 */
fun renderHtmlToAnnotatedString(htmlContent: String, baseColor: Color = Color.Unspecified): AnnotatedString {
    return try {
        // Parse raw markup; the parser decodes entities at leaf text nodes.
        val nodes = HtmlParser.parse(htmlContent)

        if (nodes.isEmpty()) {
            return AnnotatedString(decodeHtmlEntities(htmlContent))
        }

        buildAnnotatedString {
            val inlineImages = mutableMapOf<String, InlineImageData>()
            val inlineMatrixUsers = mutableMapOf<String, InlineMatrixUserChip>()
            val inlineMatrixRooms = mutableMapOf<String, InlineMatrixRoomChip>()
            var previousWasLineBreak = false

            nodes.forEach { node ->
                appendHtmlNode(
                    node = node,
                    baseStyle = SpanStyle(color = baseColor),
                    inlineImages = inlineImages,
                    inlineMatrixUsers = inlineMatrixUsers,
                    inlineMatrixRooms = inlineMatrixRooms,
                    spoilerContext = null,
                    hideContent = false,
                    previousWasLineBreak = previousWasLineBreak,
                    inlineCodeBlocks = null,
                )
                previousWasLineBreak = node is HtmlNode.LineBreak
            }
        }.let { annotatedString ->
            // Trim trailing newline if present
            if (annotatedString.text.endsWith("\n")) {
                annotatedString.subSequence(0, annotatedString.length - 1)
            } else {
                annotatedString
            }
        }
    } catch (e: Exception) {
        Log.e("Andromuks", "renderHtmlToAnnotatedString: Error rendering HTML", e)
        AnnotatedString(htmlContent)
    }
}
