package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.CacheUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.MediaUtils
import net.vrkknn.andromuks.utils.extractRoomLink
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.AppViewModel



private val matrixUserRegex = Regex("matrix:(?:/+)?(?:u|user)/(@?.+)")

private class SpoilerRenderContext(
    private val states: SnapshotStateMap<String, Boolean>
) {
    private var counter = 0
    private val usedIds = mutableSetOf<String>()

    fun start() {
        counter = 0
        usedIds.clear()
    }

    fun nextId(): String {
        val id = "spoiler_$counter"
        counter++
        usedIds.add(id)
        states.putIfAbsent(id, false)
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
    "font", "img", "details", "summary"
)

/**
 * Represents a parsed HTML node
 */
sealed class HtmlNode {
    data class Text(val content: String) : HtmlNode()
    data class Tag(
        val name: String,
        val attributes: Map<String, String>,
        val children: List<HtmlNode>
    ) : HtmlNode()
    data class LineBreak(val dummy: Unit = Unit) : HtmlNode()
}

/**
 * Simple HTML parser that handles the allowed Matrix HTML tags
 */
object HtmlParser {
    /**
     * Parse sanitized HTML string into a tree of HtmlNodes
     */
    fun parse(html: String, preserveWhitespace: Boolean = false): List<HtmlNode> {
        val nodes = mutableListOf<HtmlNode>()
        var currentPos = 0
        
        while (currentPos < html.length) {
            val nextTagStart = html.indexOf('<', currentPos)
            
            if (nextTagStart == -1) {
                // No more tags, add remaining text
                val text = html.substring(currentPos)
                if (preserveWhitespace) {
                    if (text.isNotEmpty()) {
                        nodes.add(HtmlNode.Text(text))
                    }
                } else {
                    // Only trim trailing whitespace, preserve leading spaces
                    val trimmedText = text.trimEnd()
                    if (trimmedText.isNotEmpty()) {
                        nodes.add(HtmlNode.Text(trimmedText))
                    }
                }
                break
            }
            
            // Add text before the tag
            if (nextTagStart > currentPos) {
                val text = html.substring(currentPos, nextTagStart)
                // Use isNotEmpty() instead of isNotBlank() to preserve spaces between tags
                if (text.isNotEmpty()) {
                    nodes.add(HtmlNode.Text(text))
                }
            }
            
            // Find the end of the tag
            val tagEnd = html.indexOf('>', nextTagStart)
            if (tagEnd == -1) {
                // No closing '>' found - this might be text that looks like a tag (e.g., "<--")
                // Treat everything from the '<' onwards as text
                val text = html.substring(nextTagStart)
                if (preserveWhitespace) {
                    if (text.isNotEmpty()) {
                        nodes.add(HtmlNode.Text(text))
                    }
                } else {
                    val trimmedText = text.trimEnd()
                    if (trimmedText.isNotEmpty()) {
                        nodes.add(HtmlNode.Text(trimmedText))
                    }
                }
                break
            }
            
            val tagContent = html.substring(nextTagStart + 1, tagEnd)
            
            // Check if it's a closing tag
            if (tagContent.startsWith("/")) {
                // This is a closing tag, we'll handle it during recursive parsing
                currentPos = tagEnd + 1
                continue
            }
            
            // Check if it's a self-closing tag
            val isSelfClosing = tagContent.endsWith("/")
            val actualTagContent = if (isSelfClosing) tagContent.dropLast(1).trim() else tagContent
            
            // Parse tag name and attributes
            val parts = actualTagContent.split(Regex("\\s+"), limit = 2)
            val tagName = parts[0].lowercase()
            val attributesStr = if (parts.size > 1) parts[1] else ""
            
            // Drop mx-reply blocks entirely (Matrix rich reply fallback)
            if (tagName == "mx-reply") {
                val closingTagPos = findMatchingClosingTag(html, tagEnd + 1, tagName)
                if (closingTagPos != -1) {
                    currentPos = closingTagPos + "</$tagName>".length
                } else {
                    currentPos = tagEnd + 1
                }
                continue
            }

            // Only process allowed tags
            if (!ALLOWED_HTML_TAGS.contains(tagName)) {
                Log.w("Andromuks", "HtmlParser: Skipping disallowed tag: $tagName")
                currentPos = tagEnd + 1
                continue
            }
            
            // Parse attributes
            val attributes = parseAttributes(attributesStr)
            
            // Handle self-closing tags (br, hr, img)
            if (isSelfClosing || tagName in setOf("br", "hr", "img")) {
                when (tagName) {
                    "br" -> nodes.add(HtmlNode.LineBreak())
                    "hr" -> nodes.add(HtmlNode.Tag("hr", emptyMap(), emptyList()))
                    "img" -> {
                        // Validate img src is MXC URL
                        val src = attributes["src"] ?: ""
                        if (src.startsWith("mxc://") || src.startsWith("_gomuks/media/")) {
                            nodes.add(HtmlNode.Tag(tagName, attributes, emptyList()))
                        } else if (src.startsWith("http://") || src.startsWith("https://")) {
                            Log.w("Andromuks", "HtmlParser: Refusing to load image from HTTP(S) URL: $src")
                            // Add alt text as fallback
                            val alt = attributes["alt"] ?: attributes["title"] ?: "[Image]"
                            nodes.add(HtmlNode.Text(alt))
                        } else {
                            nodes.add(HtmlNode.Tag(tagName, attributes, emptyList()))
                        }
                    }
                    else -> nodes.add(HtmlNode.Tag(tagName, attributes, emptyList()))
                }
                currentPos = tagEnd + 1
                continue
            }
            
            // Find matching closing tag
            val closingTag = "</$tagName>"
            val closingTagPos = findMatchingClosingTag(html, tagEnd + 1, tagName)
            
            if (closingTagPos == -1) {
                Log.w("Andromuks", "HtmlParser: No closing tag found for: $tagName")
                currentPos = tagEnd + 1
                continue
            }
            
            // Parse children recursively
            val innerHtml = html.substring(tagEnd + 1, closingTagPos)
            val childPreserveWhitespace = preserveWhitespace || tagName == "pre"
            val children = parse(innerHtml, childPreserveWhitespace)
            
            nodes.add(HtmlNode.Tag(tagName, attributes, children))
            currentPos = closingTagPos + closingTag.length
        }
        
        return nodes
    }
    
    /**
     * Find the matching closing tag, handling nested tags
     */
    private fun findMatchingClosingTag(html: String, startPos: Int, tagName: String): Int {
        var depth = 1
        var currentPos = startPos
        val openingTag = "<$tagName"
        val closingTag = "</$tagName>"
        
        while (currentPos < html.length && depth > 0) {
            val nextOpening = html.indexOf(openingTag, currentPos)
            val nextClosing = html.indexOf(closingTag, currentPos)
            
            if (nextClosing == -1) {
                return -1 // No closing tag found
            }
            
            // Check if there's a nested opening tag before the closing tag
            if (nextOpening != -1 && nextOpening < nextClosing) {
                // Make sure it's actually a tag start, not part of text
                val afterTag = nextOpening + openingTag.length
                if (afterTag < html.length && (html[afterTag] == '>' || html[afterTag].isWhitespace())) {
                    depth++
                    currentPos = afterTag
                } else {
                    currentPos = nextOpening + 1
                }
            } else {
                depth--
                if (depth == 0) {
                    return nextClosing
                }
                currentPos = nextClosing + closingTag.length
            }
        }
        
        return -1
    }
    
    /**
     * Parse HTML attributes from a string
     */
    private fun parseAttributes(attributesStr: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        
        // Improved regex to properly handle quoted values with spaces
        // Matches: attribute="value with spaces" or attribute='value' or attribute=value
        val regex = Regex("""(\w+(?:-\w+)*)=(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        regex.findAll(attributesStr).forEach { match ->
            val name = match.groupValues[1]
            // Get value from whichever capture group matched (double quote, single quote, or no quote)
            val value = match.groupValues[2].ifEmpty { 
                match.groupValues[3].ifEmpty { 
                    match.groupValues[4] 
                }
            }
            attributes[name.lowercase()] = value
            if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlParser: Parsed attribute: $name=\"$value\"")
        }
        
        return attributes
    }
}

/**
 * Data class for inline images
 */
data class InlineImageData(
    val src: String,
    val alt: String,
    val height: Int,
    val isHidden: Boolean = false
)

data class InlineMatrixUserChip(
    val userId: String,
    val displayText: String,
    val avatarUrl: String? = null
)

data class InlineMatrixRoomChip(
    val roomLink: RoomLink,
    val displayText: String,
    val isJoined: Boolean,
    val roomName: String? = null,
    val roomAvatarUrl: String? = null
)

data class InlineCodeBlockPreview(
    val previewText: String,
    val fullCode: String,
    val totalLines: Int
)

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
            "@${raw}"
        }
        val decoded = runCatching { URLDecoder.decode(userId.removePrefix("@"), Charsets.UTF_8.name()) }.getOrNull() ?: userId.removePrefix("@")
        return "@${decoded}"
    }
    return null
}

private fun AnnotatedString.Builder.appendHtmlNode(
    node: HtmlNode,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    spoilerContext: SpoilerRenderContext? = null,
    hideContent: Boolean = false,
    previousWasLineBreak: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
) {
    when (node) {
        is HtmlNode.Text -> {
            var text = node.content
            // Garble text if requested (for spoilers) - do this FIRST on original text to preserve exact length
            text = if (hideContent) {
                maskSpoilerText(text)
            } else {
                // In HTML mode, <br> tags handle line breaks, so remove newlines from text nodes
                // to prevent double line breaks when HTML has "<br>\n"
                // Normalize tabs/multiple spaces within each line
                // Preserve single spaces around inline tags (like <strong>, <em>) for readability
                // Only normalize multiple spaces/tabs to single spaces, don't trim edges
                var normalized = text.replace("\r\n", " ")
                    .replace("\r", " ")
                    .replace("\n", " ")
                    .replace(Regex("[\\t ]+"), " ")
                // If previous node was a line break, trim leading whitespace from this text node
                // This prevents extra spaces when HTML has "<br>\nMessage"
                if (previousWasLineBreak && normalized.isNotEmpty() && normalized[0].isWhitespace()) {
                    normalized = normalized.trimStart()
                }
                // Skip text nodes that are only whitespace (e.g., newlines after <br> tags)
                // This prevents extra spaces when HTML has "<br>\n"
                if (normalized.isBlank()) {
                    return@appendHtmlNode // Skip whitespace-only text nodes
                }
                normalized
            }
            // Only append if text is not blank (shouldn't happen after normalization, but safety check)
            if (text.isNotBlank()) {
                withStyle(baseStyle) { append(text) }
            }
        }
        is HtmlNode.LineBreak -> append("\n")
        is HtmlNode.Tag -> appendHtmlTag(node, baseStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks)
    }
}

private fun AnnotatedString.Builder.appendHtmlTag(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
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
            spoilerContext = spoilerContext,
            reason = sanitizedReason,
            inlineCodeBlocks = inlineCodeBlocks
        )
        return
    }

    val styledBase = applyInlineColors(tag, baseStyle)

    when (tag.name) {
        "strong", "b" -> appendStyledChildren(tag, styledBase.copy(fontWeight = FontWeight.Bold), inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks = inlineCodeBlocks)
        "em", "i" -> appendStyledChildren(tag, styledBase.copy(fontStyle = FontStyle.Italic), inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks = inlineCodeBlocks)
        "u" -> {
            val newStyle = styledBase.copy(textDecoration = (styledBase.textDecoration ?: TextDecoration.None) + TextDecoration.Underline)
            appendStyledChildren(tag, newStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks = inlineCodeBlocks)
        }
        "s", "del", "strike" -> {
            val newStyle = styledBase.copy(textDecoration = (styledBase.textDecoration ?: TextDecoration.None) + TextDecoration.LineThrough)
            appendStyledChildren(tag, newStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks = inlineCodeBlocks)
        }
        "ins" -> {
            val newStyle = styledBase.copy(textDecoration = (styledBase.textDecoration ?: TextDecoration.None) + TextDecoration.Underline)
            appendStyledChildren(tag, newStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks = inlineCodeBlocks)
        }
        "code" -> appendStyledChildren(tag, styledBase.copy(fontFamily = FontFamily.Monospace), inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks = inlineCodeBlocks)
        "span", "font" -> appendSpoilerOrStyledChildren(tag, styledBase, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks)
        "br" -> append("\n")
        "hr" -> appendHorizontalRule()
        "h1", "h2", "h3", "h4", "h5", "h6" -> appendHeader(tag, styledBase, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks)
        "p", "div" -> appendBlock(tag, styledBase, inlineImages, inlineMatrixUsers, spoilerContext, inlineCodeBlocks)
        "blockquote" -> appendBlockQuote(tag, styledBase, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks)
        "ul" -> appendUnorderedList(tag, styledBase, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks)
        "ol" -> appendOrderedList(tag, styledBase, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks)
        "a" -> appendAnchor(tag, styledBase, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks)
        "img" -> appendImage(tag, inlineImages, hideContent)
        "pre" -> {
            // Check if this is a code block (has <code> inside <pre>)
            val hasCodeTag = tag.children.any { child ->
                child is HtmlNode.Tag && child.name == "code"
            }
            if (hasCodeTag && inlineCodeBlocks != null) {
                // This is a code block - render truncated preview
                appendCodeBlockPreview(tag, styledBase, inlineImages, inlineMatrixUsers, inlineCodeBlocks)
            } else {
                // Regular pre block
                appendPreformattedBlock(tag, styledBase, inlineImages, inlineMatrixUsers)
            }
        }
        else -> tag.children.forEach { appendHtmlNode(it, styledBase, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks = inlineCodeBlocks) }
    }
}

private fun AnnotatedString.Builder.appendStyledChildren(
    tag: HtmlNode.Tag,
    style: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    initialPreviousWasLineBreak: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
) {
    var previousWasLineBreak = initialPreviousWasLineBreak
    tag.children.forEach { child ->
        appendHtmlNode(child, style, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, previousWasLineBreak, inlineCodeBlocks)
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
        Pair(reason!!, contentNodes!!)
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
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
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
                spoilerContext = spoilerContext,
                reason = null,
                inlineCodeBlocks = inlineCodeBlocks
            )
            return
        }
    }
    
    // Regular span/font - process children with optional color/background
    val styled = applyInlineColors(tag, baseStyle)
    appendStyledChildren(tag, styled, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, inlineCodeBlocks = inlineCodeBlocks)
}

private fun AnnotatedString.Builder.appendBlock(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    spoilerContext: SpoilerRenderContext?,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
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
                        spoilerContext = spoilerContext,
                        reason = reason,
                        inlineCodeBlocks = inlineCodeBlocks
                    )
                } else {
                    var wasLineBreak = previousWasLineBreak
                    contentNodes.forEach {
                        appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers, null, hideContent = false, previousWasLineBreak = wasLineBreak, inlineCodeBlocks = inlineCodeBlocks)
                        wasLineBreak = it is HtmlNode.LineBreak
                    }
                    previousWasLineBreak = wasLineBreak
                }
                i += 2 // Skip both nodes
                continue
            }
        }
        
        // No spoiler pattern, process normally
        appendHtmlNode(child, baseStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent = false, previousWasLineBreak = previousWasLineBreak, inlineCodeBlocks = inlineCodeBlocks)
        previousWasLineBreak = child is HtmlNode.LineBreak
        i++
    }
    append("\n")
}

private fun AnnotatedString.Builder.appendHeader(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
) {
    if (length > 0 && !endsWithNewline()) append("\n")
    
    // Apply header styling - bold text
    // Note: We use just bold for now to avoid fontSize issues with TextUnit.Unspecified
    val headerStyle = baseStyle.copy(
        fontWeight = FontWeight.Bold
    )
    
    tag.children.forEach { appendHtmlNode(it, headerStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, previousWasLineBreak = false, inlineCodeBlocks = inlineCodeBlocks) }
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
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
) {
    if (length > 0 && !endsWithNewline()) append("\n")
    
    // Style for quoted text - italic and slightly muted
    val quoteStyle = baseStyle.copy(
        fontStyle = FontStyle.Italic,
        color = baseStyle.color.copy(alpha = 0.8f)
    )
    
    // Add quote marker with styling
    withStyle(quoteStyle) {
        append("│ ")  // Use a vertical bar for a more elegant look
        
        // Collect all text from children without adding extra newlines
        val quoteText = buildString {
            fun collectText(node: HtmlNode) {
                when (node) {
                    is HtmlNode.Text -> {
                        val normalized = node.content.replace(Regex("\\s+"), " ")
                        append(normalized)
                    }
                    is HtmlNode.LineBreak -> append(" ")
                    is HtmlNode.Tag -> {
                        // For block elements inside blockquote, add space between them
                        if (node.name in setOf("p", "div") && this.isNotEmpty()) {
                            append(" ")
                        }
                        node.children.forEach { collectText(it) }
                    }
                }
            }
    tag.children.forEach { collectText(it) }
        }.trim()
        
        append(quoteText)
    }
    append("\n")
}

private fun AnnotatedString.Builder.appendUnorderedList(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
) {
    append("\n")
    tag.children.forEach { child ->
        if (child is HtmlNode.Tag && child.name == "li") {
            append("• ")
            child.children.forEach { appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, previousWasLineBreak = false, inlineCodeBlocks = inlineCodeBlocks) }
            append("\n")
        }
    }
}

private fun AnnotatedString.Builder.appendSpoilerNodes(
    nodes: List<HtmlNode>,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>,
    spoilerContext: SpoilerRenderContext,
    reason: String?,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
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
            spoilerContext = spoilerContext,
            hideContent = !revealed,
            previousWasLineBreak = false,
            inlineCodeBlocks = inlineCodeBlocks
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
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
) {
    append("\n")
    var index = 1
    tag.children.forEach { child ->
        if (child is HtmlNode.Tag && child.name == "li") {
            append("${index}. ")
            child.children.forEach { appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, previousWasLineBreak = false, inlineCodeBlocks = inlineCodeBlocks) }
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
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
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
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>
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
        totalLines = lines.size
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
    val regex = Regex("""(?i)\b${Regex.escape(key)}\s*:\s*([^;]+)""")
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

private fun buildPlainTextAnnotatedStringWithCode(
    text: String,
    linkStyle: SpanStyle,
    codeStyle: SpanStyle
): AnnotatedString {
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
    spoilerContext: SpoilerRenderContext?,
    hideContent: Boolean = false,
    inlineCodeBlocks: MutableMap<String, InlineCodeBlockPreview>? = null
) {
    val href = tag.attributes["href"] ?: ""
    
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
    
    // Check for Matrix room links
    val roomLink = extractRoomLink(href)
    if (roomLink != null) {
        val linkStyle = baseStyle.copy(color = Color(0xFF1A73E8), textDecoration = TextDecoration.Underline)
        pushStringAnnotation("ROOM_LINK", href)
        var previousWasLineBreak = false
        tag.children.forEach { child ->
            appendHtmlNode(child, linkStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, previousWasLineBreak, inlineCodeBlocks = inlineCodeBlocks)
            previousWasLineBreak = child is HtmlNode.LineBreak
        }
        pop()
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
            appendHtmlNode(child, linkStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, previousWasLineBreak, inlineCodeBlocks = inlineCodeBlocks)
            previousWasLineBreak = child is HtmlNode.LineBreak
        }
        val annotationEnd = length
        pop()
        if (BuildConfig.DEBUG) Log.d("Andromuks", "appendAnchor: URL annotation added from $annotationStart to $annotationEnd for href=$href")
    } else {
        if (BuildConfig.DEBUG) Log.w("Andromuks", "appendAnchor: Empty href, not adding URL annotation")
        // No href, just render children with base style (shouldn't happen for valid HTML)
        var previousWasLineBreak = false
        tag.children.forEach { child ->
            appendHtmlNode(child, baseStyle, inlineImages, inlineMatrixUsers, spoilerContext, hideContent, previousWasLineBreak)
            previousWasLineBreak = child is HtmlNode.LineBreak
        }
    }
}

private fun AnnotatedString.Builder.appendImage(
    tag: HtmlNode.Tag,
    inlineImages: MutableMap<String, InlineImageData>,
    hideContent: Boolean
) {
    val src = tag.attributes["src"] ?: tag.attributes["data-mxc"] ?: ""
    val alt = tag.attributes["alt"] ?: tag.attributes["title"] ?: ""
    val height = tag.attributes["height"]?.toIntOrNull() ?: 32
    if (src.isNotBlank()) {
        val id = "inline_img_${inlineImages.size}"
        inlineImages[id] = InlineImageData(src, alt, height, isHidden = hideContent)
        appendInlineContent(id, "\u200B")
    } else {
        append(if (hideContent) maskSpoilerText(alt) else alt)
    }
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
        "&quot" to "\"",   // Also without semicolon
        "&apos;" to "'",
        "&apos" to "'",    // Also without semicolon
        "&amp;" to "&",
        "&amp" to "&",     // Also without semicolon
        "&lt;" to "<",
        "&lt" to "<",      // Also without semicolon
        "&gt;" to ">",
        "&gt" to ">",      // Also without semicolon
        "&nbsp;" to " ",
        "&nbsp" to " ",    // Also without semicolon
        "&copy;" to "©",
        "&copy" to "©",    // Also without semicolon
        "&reg;" to "®",
        "&reg" to "®",     // Also without semicolon
        "&euro;" to "€",
        "&euro" to "€",    // Also without semicolon
        "&pound;" to "£",
        "&pound" to "£",   // Also without semicolon
        "&yen;" to "¥",
        "&yen" to "¥",     // Also without semicolon
        "&cent;" to "¢",
        "&cent" to "¢"     // Also without semicolon
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
    val sanitizedHtml = event.localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
    
    if (BuildConfig.DEBUG) {
        if (sanitizedHtml != null) {
            Log.d("Andromuks", "extractSanitizedHtml: Found sanitized_html for event ${event.eventId}, length: ${sanitizedHtml.length}, preview: ${sanitizedHtml.take(100)}")
        } else {
            Log.d("Andromuks", "extractSanitizedHtml: No sanitized_html found for event ${event.eventId}, localContent is null: ${event.localContent == null}")
        }
    }
    
    // Decode HTML entities before returning
    return sanitizedHtml?.let { decodeHtmlEntities(it) }
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
        "m.text", "m.emote", "m.notice",
        "m.image", "m.file", "m.audio", "m.video"
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
    onCodeBlockClick: (String) -> Unit = {} // Callback for code block clicks
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
            if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: Using provided htmlContent for event ${event.eventId}, length: ${htmlContent.length}, preview: ${htmlContent.take(100)}")
            decodeHtmlEntities(htmlContent)
        } else {
            // Extract from event - prioritize sanitized_html over formatted_body
            val sanitized = extractSanitizedHtml(event)
            if (sanitized != null) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: Using sanitized_html for event ${event.eventId}, length: ${sanitized.length}, preview: ${sanitized.take(100)}")
                sanitized
            } else {
                val formattedBody = event.decrypted?.optString("formatted_body")?.takeIf { it.isNotBlank() }
                    ?: event.content?.optString("formatted_body")?.takeIf { it.isNotBlank() }
                if (formattedBody != null) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: Using formatted_body for event ${event.eventId}, length: ${formattedBody.length}, preview: ${formattedBody.take(100)}")
                    decodeHtmlEntities(formattedBody)
                } else {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: No HTML content found for event ${event.eventId}")
                    null
                }
            }
        }
        result
    }
    
    val plainTextBody = if (sanitizedHtml == null) {
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
    
    // Parse HTML
    val nodes = remember(sanitizedHtml) {
        if (sanitizedHtml == null) {
            emptyList()
        } else {
            try {
                HtmlParser.parse(sanitizedHtml)
            } catch (e: Exception) {
                Log.e("Andromuks", "HtmlMessageText: Failed to parse HTML", e)
                emptyList()
            }
        }
    }
    
    // OPPORTUNISTIC PROFILE LOADING: Extract Matrix user IDs from HTML and request profiles
    LaunchedEffect(nodes, event.roomId) {
        if (appViewModel != null && nodes.isNotEmpty()) {
            val userIds = extractMatrixUserIdsFromNodes(nodes)
            userIds.forEach { userId ->
                // Check if we already have the profile
                val existingProfile = appViewModel.getUserProfile(userId, event.roomId)
                if (existingProfile == null) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: Requesting profile on-demand for $userId from HTML")
                    appViewModel.requestUserProfileOnDemand(userId, event.roomId)
                }
            }
        }
    }
    
    // Render to AnnotatedString with inline images
    val inlineImages = remember { mutableMapOf<String, InlineImageData>() }
    val inlineMatrixUsers = remember { mutableMapOf<String, InlineMatrixUserChip>() }
    val inlineCodeBlocks = remember { mutableMapOf<String, InlineCodeBlockPreview>() } // Map of code block IDs to preview data
    val spoilerStates = remember { mutableStateMapOf<String, Boolean>() }
    val spoilerContext = remember { SpoilerRenderContext(spoilerStates) }

    val linkStyle = SpanStyle(color = Color(0xFF1A73E8), textDecoration = TextDecoration.Underline)
    val renderedString = if (sanitizedHtml == null) {
        buildPlainTextAnnotatedStringWithCode(
            plainTextBody,
            linkStyle,
            SpanStyle(fontFamily = FontFamily.Monospace)
        )
    } else {
        try {
            spoilerContext.start()
            inlineImages.clear()
            inlineMatrixUsers.clear()
            inlineCodeBlocks.clear()
            buildAnnotatedString {
                var i = 0
                var previousWasLineBreak = false
                while (i < nodes.size) {
                    val node = nodes[i]

                    // Single spoiler span without reason
                    if (node is HtmlNode.Tag && node.name == "span") {
                        val classAttr = node.attributes["class"] ?: ""
                        if (classAttr.contains("hicli-spoiler") && node.children.isNotEmpty()) {
                            appendSpoilerNodes(
                                nodes = node.children,
                                baseStyle = SpanStyle(color = color),
                                inlineImages = inlineImages,
                                inlineMatrixUsers = inlineMatrixUsers,
                                spoilerContext = spoilerContext,
                                reason = null,
                                inlineCodeBlocks = inlineCodeBlocks
                            )
                            previousWasLineBreak = false
                            i++
                            continue
                        }
                    }

                    // Reason + spoiler pattern
                    if (i + 1 < nodes.size) {
                        val spoilerData = extractSpoilerData(listOf(node, nodes[i + 1]))
                        if (spoilerData != null) {
                            val (reason, contentNodes) = spoilerData
                            if (contentNodes.isNotEmpty()) {
                                appendSpoilerNodes(
                                    nodes = contentNodes,
                                    baseStyle = SpanStyle(color = color),
                                    inlineImages = inlineImages,
                                    inlineMatrixUsers = inlineMatrixUsers,
                                    spoilerContext = spoilerContext,
                                    reason = reason,
                                    inlineCodeBlocks = inlineCodeBlocks
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
                        spoilerContext = spoilerContext,
                        previousWasLineBreak = wasLineBreak,
                        inlineCodeBlocks = inlineCodeBlocks
                    )
                    previousWasLineBreak = node is HtmlNode.LineBreak
                    i++
                }
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "HtmlMessageText: Failed to render HTML", e)
            AnnotatedString("")
        }.also {
            spoilerContext.cleanup()
        }
    }
    val annotatedString = if (sanitizedHtml != null && renderedString.text.endsWith("\n")) {
        renderedString.subSequence(0, renderedString.length - 1) as AnnotatedString
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
    
    val inlineContentMap = remember(annotatedString, inlineImages.toMap(), inlineMatrixUsers.toMap(), inlineCodeBlocks.toMap(), onMatrixUserClick, onCodeBlockClick, density, chipTextStyle, textMeasurer, textLineHeight, primaryColor, isEmojiOnly, color, bodyTextStyle) {
        val map = mutableMapOf<String, InlineTextContent>()
        inlineImages.forEach { (id, imageData) ->
            // Limit image height to text line height, but use 2x size for emoji-only messages
            val baseMaxHeight = minOf(imageData.height, textLineHeight)
            val maxHeight = if (isEmojiOnly) {
                baseMaxHeight * 2
            } else {
                baseMaxHeight
            }
            map[id] = InlineTextContent(
                Placeholder(
                    width = maxHeight.sp,
                    height = maxHeight.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                InlineImage(
                    src = imageData.src,
                    alt = imageData.alt,
                    height = maxHeight,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    isHidden = imageData.isHidden
                )
            }
        }
        inlineMatrixUsers.forEach { (id, chip) ->
            val textLayout = textMeasurer.measure(
                text = AnnotatedString(chip.displayText),
                style = chipTextStyle.copy(color = primaryColor)
            )
            val textWidthDp = with(density) { textLayout.size.width.toDp() }
            val widthSp = with(density) { textWidthDp.value.sp }
            val heightSp = with(density) { textLayout.size.height.toDp().value.sp }
            map[id] = InlineTextContent(
                Placeholder(
                    width = widthSp,
                    height = heightSp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Text(
                    text = chip.displayText,
                    style = chipTextStyle,
                    color = primaryColor,
                    modifier = Modifier.clickable { onMatrixUserClick(chip.userId) }
                )
            }
        }
        // Code blocks are now rendered directly in the text flow, not as inline content
        // The inlineCodeBlocks map is still used to store full code for click handling
        map
    }
    
    if (annotatedString.text.isEmpty()) {
        // Fallback if rendering failed
        val content = event.content ?: event.decrypted
        val body = content?.optString("body", "")?.let { decodeHtmlEntities(it) } ?: ""
        val baseStyle = MaterialTheme.typography.bodyMedium
        val textStyle = if (isEmojiOnly) {
            baseStyle.copy(fontSize = baseStyle.fontSize * 2)
        } else {
            baseStyle
        }
        Text(
            text = body,
            style = textStyle,
            modifier = modifier,
            color = color
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
                            val spoilerAnnotation = annotatedString.getStringAnnotations(tag = "SPOILER", start = offset, end = offset).firstOrNull()
                            if (spoilerAnnotation != null) {
                                up.consume()
                                spoilerContext.toggle(spoilerAnnotation.item)
                                return@awaitEachGesture
                            }

                            // Check for code block annotation first
                            val codeBlockAnnotation = annotatedString.getStringAnnotations(tag = "CODE_BLOCK", start = offset, end = offset).firstOrNull()
                            if (codeBlockAnnotation != null) {
                                up.consume()
                                val codeBlockId = codeBlockAnnotation.item
                                val codeBlock = inlineCodeBlocks[codeBlockId]
                                if (codeBlock != null) {
                                    onCodeBlockClick(codeBlock.fullCode)
                                }
                                return@awaitEachGesture
                            }

                            val hasMatrixUser = annotatedString.getStringAnnotations(tag = "MATRIX_USER", start = offset, end = offset).isNotEmpty()
                            val hasRoomLink = annotatedString.getStringAnnotations(tag = "ROOM_LINK", start = offset, end = offset).isNotEmpty()
                            val hasUrl = annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset).isNotEmpty()
                            
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
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: room link tapped for ${roomLink.roomIdOrAlias}")
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
                                                val userId = if (rawId.startsWith("@")) rawId else "@${rawId}"
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: matrix:u link tapped for $userId")
                                                onMatrixUserClick(userId)
                                            }
                                            url.startsWith("https://matrix.to/#/") -> {
                                                val encodedPart = url.removePrefix("https://matrix.to/#/")
                                                val decoded = runCatching { URLDecoder.decode(encodedPart, Charsets.UTF_8.name()) }
                                                    .getOrDefault(encodedPart)
                                                if (decoded.startsWith("@")) {
                                                    // User link
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: matrix.to link tapped for $decoded")
                                                    onMatrixUserClick(decoded)
                                                } else {
                                                    // Check if it's a room link (starts with ! or #)
                                                    val roomLink = extractRoomLink(url)
                                                    if (roomLink != null) {
                                                        if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: matrix.to room link tapped for ${roomLink.roomIdOrAlias}")
                                                        onRoomLinkClick(roomLink)
                                                    } else {
                                                        // Fallback to opening in browser for unrecognized matrix.to links
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
                                            url.startsWith("matrix:roomid/") || url.startsWith("matrix:/roomid/") || url.startsWith("matrix:r/") || url.startsWith("matrix:/r/") -> {
                                                // Matrix room link in matrix: URI format
                                                val roomLink = extractRoomLink(url)
                                                if (roomLink != null) {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "HtmlMessageText: matrix: room link tapped for ${roomLink.roomIdOrAlias}")
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
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * 2
                )
            } else {
                MaterialTheme.typography.bodyMedium.copy(color = color)
            },
            inlineContent = inlineContentMap,
            onTextLayout = { layoutResult ->
                textLayoutResult = layoutResult
            }
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
    height: Int,
    homeserverUrl: String,
    authToken: String,
    isHidden: Boolean
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
    var cachedFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(mxcUrl) {
        cachedFile = mxcUrl?.let { IntelligentMediaCache.getCachedFile(context, it) }
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
            when {
                src.startsWith("mxc://") -> {
                    MediaUtils.mxcToHttpUrl(src, homeserverUrl)
                }
                src.startsWith("_gomuks/media/") -> {
                    // Already in gomuks format, just prepend homeserver URL
                    "$homeserverUrl/$src"
                }
                else -> {
                    Log.w("Andromuks", "InlineImage: Invalid image source: $src")
                    null
                }
            }
        }
    }
    
    // NOTE: Coil handles caching automatically with memoryCachePolicy and diskCachePolicy
    // No need to manually download - would cause duplicate requests (Coil + okhttp)
    
    if (isHidden) {
        // Render a placeholder with the same size to avoid layout changes.
        Box(
            modifier = Modifier
                .size(height.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
        )
    } else if (imageUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    if (cachedFile == null) {
                        addHeader("Cookie", "gomuks_auth=$authToken")
                    }
                }
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            imageLoader = imageLoader,
            contentDescription = alt,
            modifier = Modifier.size(height.dp),
            onError = { }
        )
    } else {
        // Fallback to alt text
        Text(text = alt, fontSize = (height * 0.6).sp)
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
        // Decode HTML entities first
        val decoded = decodeHtmlEntities(htmlContent)
        
        // Parse HTML into nodes
        val nodes = HtmlParser.parse(decoded)
        
        // Convert to SpannableStringBuilder with styles
        val builder = android.text.SpannableStringBuilder()
        
        fun appendNodeToSpannable(node: HtmlNode) {
            when (node) {
                is HtmlNode.Text -> builder.append(node.content)
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
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        "em", "i" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        "u" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.UnderlineSpan(),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        "s", "del" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.StrikethroughSpan(),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        "code" -> {
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.TypefaceSpan("monospace"),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        "pre" -> {
                            if (builder.isNotEmpty() && !builder.toString().endsWith("\n")) builder.append("\n")
                            node.children.forEach { appendNodeToSpannable(it) }
                            builder.setSpan(
                                android.text.style.TypefaceSpan("monospace"),
                                startIndex,
                                builder.length,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
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
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
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
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
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
                                    builder.append("${index}. ")
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
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
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

