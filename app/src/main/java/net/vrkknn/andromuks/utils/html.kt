package net.vrkknn.andromuks.utils

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.net.URLDecoder
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.CacheUtils
import net.vrkknn.andromuks.utils.MediaCache
import net.vrkknn.andromuks.utils.MediaUtils

private val matrixUserRegex = Regex("matrix:(?:/+)?(?:u|user)/(@?.+)")

/**
 * Allowed HTML tags according to Matrix spec for safe rendering
 */
private val ALLOWED_HTML_TAGS = setOf(
    "del", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "p", "a", "ul", "ol", 
    "sup", "sub", "li", "b", "i", "u", "strong", "em", "s", "code", "hr", "br", 
    "div", "table", "thead", "tbody", "tr", "th", "td", "caption", "pre", "span", 
    "img", "details", "summary"
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
    fun parse(html: String): List<HtmlNode> {
        val nodes = mutableListOf<HtmlNode>()
        var currentPos = 0
        
        while (currentPos < html.length) {
            val nextTagStart = html.indexOf('<', currentPos)
            
            if (nextTagStart == -1) {
                // No more tags, add remaining text
                val text = html.substring(currentPos).trim()
                if (text.isNotEmpty()) {
                    nodes.add(HtmlNode.Text(text))
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
                // Malformed HTML, stop parsing
                Log.w("Andromuks", "HtmlParser: Malformed HTML, missing closing '>'")
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
            val children = parse(innerHtml)
            
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
            Log.d("Andromuks", "HtmlParser: Parsed attribute: $name=\"$value\"")
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
    val height: Int
)

data class InlineMatrixUserChip(
    val userId: String,
    val displayText: String,
    val avatarUrl: String? = null
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
        val decoded = runCatching { URLDecoder.decode(raw.removePrefix("@"), Charsets.UTF_8.name()) }.getOrNull() ?: raw.removePrefix("@")
        return "@${decoded}"
    }
    return null
}

private fun AnnotatedString.Builder.appendHtmlNode(
    node: HtmlNode,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
) {
    when (node) {
        is HtmlNode.Text -> withStyle(baseStyle) { append(node.content) }
        is HtmlNode.LineBreak -> append("\n")
        is HtmlNode.Tag -> appendHtmlTag(node, baseStyle, inlineImages, inlineMatrixUsers)
    }
}

private fun AnnotatedString.Builder.appendHtmlTag(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
) {
    val styleAttr = tag.attributes["style"]?.lowercase() ?: ""
    if (styleAttr.contains("display") && styleAttr.contains("none")) {
        return
    }

    when (tag.name) {
        "strong", "b" -> appendStyledChildren(tag, baseStyle.copy(fontWeight = FontWeight.Bold), inlineImages, inlineMatrixUsers)
        "em", "i" -> appendStyledChildren(tag, baseStyle.copy(fontStyle = FontStyle.Italic), inlineImages, inlineMatrixUsers)
        "u" -> {
            val newStyle = baseStyle.copy(textDecoration = (baseStyle.textDecoration ?: TextDecoration.None) + TextDecoration.Underline)
            appendStyledChildren(tag, newStyle, inlineImages, inlineMatrixUsers)
        }
        "s", "del" -> {
            val newStyle = baseStyle.copy(textDecoration = (baseStyle.textDecoration ?: TextDecoration.None) + TextDecoration.LineThrough)
            appendStyledChildren(tag, newStyle, inlineImages, inlineMatrixUsers)
        }
        "code" -> appendStyledChildren(tag, baseStyle.copy(fontFamily = FontFamily.Monospace), inlineImages, inlineMatrixUsers)
        "span" -> appendStyledChildren(tag, baseStyle, inlineImages, inlineMatrixUsers)
        "br" -> append("\n")
        "p", "div" -> appendBlock(tag, baseStyle, inlineImages, inlineMatrixUsers)
        "blockquote" -> appendBlockQuote(tag, baseStyle, inlineImages, inlineMatrixUsers)
        "ul" -> appendUnorderedList(tag, baseStyle, inlineImages, inlineMatrixUsers)
        "ol" -> appendOrderedList(tag, baseStyle, inlineImages, inlineMatrixUsers)
        "a" -> appendAnchor(tag, baseStyle, inlineImages, inlineMatrixUsers)
        "img" -> appendImage(tag, inlineImages)
        else -> tag.children.forEach { appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers) }
    }
}

private fun AnnotatedString.Builder.appendStyledChildren(
    tag: HtmlNode.Tag,
    style: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
) {
    tag.children.forEach { appendHtmlNode(it, style, inlineImages, inlineMatrixUsers) }
}

private fun AnnotatedString.Builder.appendBlock(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
) {
    if (length > 0 && !endsWithNewline()) append("\n")
    tag.children.forEach { appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers) }
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
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
) {
    append("\n")
    append("> ")
    tag.children.forEach { appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers) }
    append("\n")
}

private fun AnnotatedString.Builder.appendUnorderedList(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
) {
    append("\n")
    tag.children.forEach { child ->
        if (child is HtmlNode.Tag && child.name == "li") {
            append("• ")
            child.children.forEach { appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers) }
            append("\n")
        }
    }
}

private fun AnnotatedString.Builder.appendOrderedList(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
) {
    append("\n")
    var index = 1
    tag.children.forEach { child ->
        if (child is HtmlNode.Tag && child.name == "li") {
            append("${index}. ")
            child.children.forEach { appendHtmlNode(it, baseStyle, inlineImages, inlineMatrixUsers) }
            append("\n")
            index++
        }
    }
}

private fun AnnotatedString.Builder.appendAnchor(
    tag: HtmlNode.Tag,
    baseStyle: SpanStyle,
    inlineImages: MutableMap<String, InlineImageData>,
    inlineMatrixUsers: MutableMap<String, InlineMatrixUserChip>
) {
    val href = tag.attributes["href"] ?: ""
    val matrixUser = extractMatrixUserId(href)
    if (matrixUser != null) {
        val textBuilder = StringBuilder()
        tag.children.forEach { collectPlainText(it, textBuilder) }
        val displayText = textBuilder.toString().ifBlank { matrixUser }
        val chipId = "matrix_user_${inlineMatrixUsers.size}"
        inlineMatrixUsers[chipId] = InlineMatrixUserChip(matrixUser, displayText)
        if (length > 0 && !endsWithWhitespace()) {
            append(" ")
        }
        pushStringAnnotation("MATRIX_USER", matrixUser)
        appendInlineContent(chipId, displayText)
        pop()
    } else {
        val linkStyle = baseStyle.copy(color = Color(0xFF1A73E8), textDecoration = TextDecoration.Underline)
        pushStringAnnotation("URL", href)
        tag.children.forEach { appendHtmlNode(it, linkStyle, inlineImages, inlineMatrixUsers) }
        pop()
    }
}

private fun AnnotatedString.Builder.appendImage(
    tag: HtmlNode.Tag,
    inlineImages: MutableMap<String, InlineImageData>
) {
    val src = tag.attributes["src"] ?: tag.attributes["data-mxc"] ?: ""
    val alt = tag.attributes["alt"] ?: tag.attributes["title"] ?: ""
    val height = tag.attributes["height"]?.toIntOrNull() ?: 32
    if (src.isNotBlank()) {
        val id = "inline_img_${inlineImages.size}"
        inlineImages[id] = InlineImageData(src, alt, height)
        appendInlineContent(id, "\u200B")
    } else {
        append(alt)
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
    
    // Decode HTML entities before returning
    return sanitizedHtml?.let { decodeHtmlEntities(it) }
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

    val hasFormattedBody = !content.optString("formatted_body", null).isNullOrBlank()

    return msgType in supportedTypes && hasFormattedBody
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
    onMatrixUserClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val sanitizedHtml = remember(event) {
        val sanitized = extractSanitizedHtml(event)
        sanitized ?: run {
            val formattedBody = event.decrypted?.optString("formatted_body")?.takeIf { it.isNotBlank() }
                ?: event.content?.optString("formatted_body")?.takeIf { it.isNotBlank() }
            formattedBody?.let { decodeHtmlEntities(it) }
        }
    }
    
    if (sanitizedHtml == null) {
        // Fallback to plain text body
        val content = event.content ?: event.decrypted
        val body = content?.optString("body", "")?.let { decodeHtmlEntities(it) } ?: ""
        Text(
            text = body,
            modifier = modifier,
            color = color
        )
        return
    }
    
    // Parse HTML
    val nodes = remember(sanitizedHtml) {
        try {
            HtmlParser.parse(sanitizedHtml)
        } catch (e: Exception) {
            Log.e("Andromuks", "HtmlMessageText: Failed to parse HTML", e)
            emptyList()
        }
    }
    
    // Render to AnnotatedString with inline images
    val inlineImages = remember { mutableMapOf<String, InlineImageData>() }
    val inlineMatrixUsers = remember { mutableMapOf<String, InlineMatrixUserChip>() }
    val annotatedString = remember(nodes, color) {
        try {
            inlineImages.clear()
            inlineMatrixUsers.clear()
            buildAnnotatedString {
                nodes.forEach {
                    appendHtmlNode(it, SpanStyle(color = color), inlineImages, inlineMatrixUsers)
                }
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "HtmlMessageText: Failed to render HTML", e)
            AnnotatedString("")
        }
    }
    val density = LocalDensity.current
    val chipTextStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val inlineContentMap = remember(annotatedString, inlineImages.toMap(), inlineMatrixUsers.toMap(), onMatrixUserClick, density, chipTextStyle, textMeasurer) {
        val map = mutableMapOf<String, InlineTextContent>()
        inlineImages.forEach { (id, imageData) ->
            map[id] = InlineTextContent(
                Placeholder(
                    width = imageData.height.sp,
                    height = imageData.height.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                InlineImage(
                    src = imageData.src,
                    alt = imageData.alt,
                    height = imageData.height,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken
                )
            }
        }
        inlineMatrixUsers.forEach { (id, chip) ->
            val textLayout = textMeasurer.measure(
                text = AnnotatedString(chip.displayText),
                style = chipTextStyle
            )
            val textWidthDp = with(density) { textLayout.size.width.toDp() }
            val widthSp = with(density) { (textWidthDp + 28.dp).value.sp }
            val heightSp = with(density) { 28.dp.value.sp }
            map[id] = InlineTextContent(
                Placeholder(
                    width = widthSp,
                    height = heightSp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onMatrixUserClick(chip.userId) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = chip.displayText,
                            style = chipTextStyle
                        )
                    }
                }
            }
        }
        map
    }
    
    if (annotatedString.text.isEmpty()) {
        // Fallback if rendering failed
        val content = event.content ?: event.decrypted
        val body = content?.optString("body", "")?.let { decodeHtmlEntities(it) } ?: ""
        Text(
            text = body,
            modifier = modifier,
            color = color
        )
    } else {
        // Use Text with proper click detection for URL support
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        
        Text(
            text = annotatedString,
            modifier = modifier.pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    textLayoutResult?.let { layoutResult ->
                        // Get the character offset at the tap position
                        val offset = layoutResult.getOffsetForPosition(tapOffset)
                        
                        // Matrix user annotations take precedence
                        annotatedString.getStringAnnotations(tag = "MATRIX_USER", start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                onMatrixUserClick(annotation.item)
                                return@detectTapGestures
                            }

                        // Check if the tapped position has a URL annotation
                        annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                val url = annotation.item
                                when {
                                    url.startsWith("matrix:u/") -> {
                                        val rawId = url.removePrefix("matrix:u/")
                                        val userId = if (rawId.startsWith("@")) rawId else "@${rawId}"
                                        Log.d("Andromuks", "HtmlMessageText: matrix:u link tapped for $userId")
                                        onMatrixUserClick(userId)
                                    }
                                    url.startsWith("https://matrix.to/#/") -> {
                                        val encodedPart = url.removePrefix("https://matrix.to/#/")
                                        val userId = runCatching { URLDecoder.decode(encodedPart, Charsets.UTF_8.name()) }
                                            .getOrDefault(encodedPart)
                                        if (userId.startsWith("@")) {
                                            Log.d("Andromuks", "HtmlMessageText: matrix.to link tapped for $userId")
                                            onMatrixUserClick(userId)
                                        } else {
                                            // Fallback to opening in browser for non-user matrix.to links
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                context.startActivity(intent)
                                                Log.d("Andromuks", "Opening URL: $url")
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
                                            Log.d("Andromuks", "Opening URL: $url")
                                        } catch (e: Exception) {
                                            Log.e("Andromuks", "Failed to open URL: $url", e)
                                        }
                                    }
                                }
                            }
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium.copy(color = color),
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
    authToken: String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Create ImageLoader with GIF support for animated images
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    
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
    val cachedFile = remember(mxcUrl) {
        mxcUrl?.let { MediaCache.getCachedFile(context, it) }
    }
    
    // Convert to HTTP URL or use cached file
    val imageUrl = remember(src, homeserverUrl, cachedFile) {
        if (cachedFile != null) {
            // Use cached file
            Log.d("Andromuks", "InlineImage: Using cached file: ${cachedFile.absolutePath}")
            cachedFile.absolutePath
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
    
    // Download and cache if not already cached
    LaunchedEffect(mxcUrl) {
        if (cachedFile == null && mxcUrl != null) {
            coroutineScope.launch {
                val httpUrl = MediaUtils.mxcToHttpUrl(mxcUrl, homeserverUrl) ?: return@launch
                MediaCache.downloadAndCache(context, mxcUrl, httpUrl, authToken)
                // Clean up cache if needed
                MediaCache.cleanupCache(context)
            }
        }
    }
    
    if (imageUrl != null) {
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
            onError = { state ->
                if (state is coil.request.ErrorResult) {
                    CacheUtils.handleImageLoadError(
                        imageUrl = imageUrl,
                        throwable = state.throwable,
                        imageLoader = imageLoader,
                        context = "HTML Image"
                    )
                }
            }
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

