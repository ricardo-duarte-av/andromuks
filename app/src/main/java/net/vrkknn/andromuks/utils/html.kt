package net.vrkknn.andromuks.utils

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.TimelineEvent
import org.json.JSONObject

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
 * Renderer for converting HtmlNodes to Compose AnnotatedString
 */
object HtmlRenderer {
    /**
     * Convert parsed HTML nodes to an AnnotatedString with inline content for images
     */
    fun render(
        nodes: List<HtmlNode>,
        baseStyle: SpanStyle = SpanStyle(),
        inlineImages: MutableMap<String, InlineImageData> = mutableMapOf()
    ): AnnotatedString {
        return buildAnnotatedString {
            nodes.forEach { node ->
                renderNode(node, baseStyle, inlineImages)
            }
        }
    }
    
    private fun AnnotatedString.Builder.renderNode(
        node: HtmlNode,
        baseStyle: SpanStyle,
        inlineImages: MutableMap<String, InlineImageData>
    ) {
        when (node) {
            is HtmlNode.Text -> {
                withStyle(baseStyle) {
                    append(node.content)
                }
            }
            is HtmlNode.LineBreak -> {
                append("\n")
            }
            is HtmlNode.Tag -> {
                renderTag(node, baseStyle, inlineImages)
            }
        }
    }
    
    private fun AnnotatedString.Builder.renderTag(
        tag: HtmlNode.Tag,
        baseStyle: SpanStyle,
        inlineImages: MutableMap<String, InlineImageData>
    ) {
        // Skip rendering tags with display: none style (more robust checking)
        val styleAttr = tag.attributes["style"] ?: ""
        if (styleAttr.contains("display") && 
            (styleAttr.contains("none") || styleAttr.contains(": none"))) {
            // Skip this entire tag and all its children
            Log.d("Andromuks", "HtmlRenderer: Skipping tag '${tag.name}' with display:none style")
            return
        }
        
        when (tag.name) {
            // Text styling
            "strong", "b" -> {
                val style = baseStyle.copy(fontWeight = FontWeight.Bold)
                tag.children.forEach { renderNode(it, style, inlineImages) }
            }
            "em", "i" -> {
                val style = baseStyle.copy(fontStyle = FontStyle.Italic)
                tag.children.forEach { renderNode(it, style, inlineImages) }
            }
            "u" -> {
                val style = baseStyle.copy(textDecoration = TextDecoration.Underline)
                tag.children.forEach { renderNode(it, style, inlineImages) }
            }
            "s", "del" -> {
                val style = baseStyle.copy(textDecoration = TextDecoration.LineThrough)
                tag.children.forEach { renderNode(it, style, inlineImages) }
            }
            "code" -> {
                val style = baseStyle.copy(
                    fontFamily = FontFamily.Monospace,
                    background = Color.Gray.copy(alpha = 0.2f)
                )
                tag.children.forEach { renderNode(it, style, inlineImages) }
            }
            "sup" -> {
                val style = baseStyle.copy(fontSize = baseStyle.fontSize.times(0.7f))
                // TODO: Add superscript baseline shift when available
                tag.children.forEach { renderNode(it, style, inlineImages) }
            }
            "sub" -> {
                val style = baseStyle.copy(fontSize = baseStyle.fontSize.times(0.7f))
                // TODO: Add subscript baseline shift when available
                tag.children.forEach { renderNode(it, style, inlineImages) }
            }
            
            // Headings
            "h1" -> {
                append("\n")
                val style = baseStyle.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                )
                tag.children.forEach { renderNode(it, style, inlineImages) }
                append("\n")
            }
            "h2" -> {
                append("\n")
                val style = baseStyle.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                tag.children.forEach { renderNode(it, style, inlineImages) }
                append("\n")
            }
            "h3" -> {
                append("\n")
                val style = baseStyle.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                tag.children.forEach { renderNode(it, style, inlineImages) }
                append("\n")
            }
            "h4" -> {
                append("\n")
                val style = baseStyle.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                tag.children.forEach { renderNode(it, style, inlineImages) }
                append("\n")
            }
            "h5" -> {
                append("\n")
                val style = baseStyle.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                tag.children.forEach { renderNode(it, style, inlineImages) }
                append("\n")
            }
            "h6" -> {
                append("\n")
                val style = baseStyle.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                tag.children.forEach { renderNode(it, style, inlineImages) }
                append("\n")
            }
            
            // Links
            "a" -> {
                val href = tag.attributes["href"] ?: ""
                val style = baseStyle.copy(
                    color = Color.Blue,
                    textDecoration = TextDecoration.Underline
                )
                pushStringAnnotation(tag = "URL", annotation = href)
                tag.children.forEach { renderNode(it, style, inlineImages) }
                pop()
            }
            
            // Block elements
            "p", "div" -> {
                if (length > 0) {
                    append("\n")
                }
                tag.children.forEach { renderNode(it, baseStyle, inlineImages) }
                append("\n")
            }
            "blockquote" -> {
                append("\n> ")
                tag.children.forEach { renderNode(it, baseStyle, inlineImages) }
                append("\n")
            }
            "pre" -> {
                append("\n")
                val style = baseStyle.copy(
                    fontFamily = FontFamily.Monospace,
                    background = Color.Gray.copy(alpha = 0.2f)
                )
                tag.children.forEach { renderNode(it, style, inlineImages) }
                append("\n")
            }
            
            // Lists
            "ul" -> {
                append("\n")
                tag.children.forEach { child ->
                    if (child is HtmlNode.Tag && child.name == "li") {
                        append("• ")
                        child.children.forEach { renderNode(it, baseStyle, inlineImages) }
                        append("\n")
                    }
                }
            }
            "ol" -> {
                append("\n")
                var index = 1
                tag.children.forEach { child ->
                    if (child is HtmlNode.Tag && child.name == "li") {
                        append("$index. ")
                        child.children.forEach { renderNode(it, baseStyle, inlineImages) }
                        append("\n")
                        index++
                    }
                }
            }
            
            // Line break elements
            "br" -> append("\n")
            "hr" -> append("\n─────────────\n")
            
            // Images (inline)
            "img" -> {
                val src = tag.attributes["src"] ?: tag.attributes["data-mxc"] ?: ""
                val alt = tag.attributes["alt"] ?: tag.attributes["title"] ?: ""
                val heightStr = tag.attributes["height"] ?: "32"
                val height = heightStr.toIntOrNull() ?: 32
                
                if (src.isNotEmpty()) {
                    // Generate unique ID for this inline image
                    val imageId = "img_${inlineImages.size}"
                    inlineImages[imageId] = InlineImageData(src, alt, height)
                    
                    // Add placeholder for the image
                    appendInlineContent(imageId, alt)
                } else {
                    // Fallback to alt text
                    append(alt)
                }
            }
            
            // Fallback for other tags - just render children
            else -> {
                tag.children.forEach { renderNode(it, baseStyle, inlineImages) }
            }
        }
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

/**
 * Extract sanitized HTML from a timeline event
 */
fun extractSanitizedHtml(event: TimelineEvent): String? {
    // Check if event has local_content with sanitized_html
    // local_content is a top-level field in the event JSON, parsed into TimelineEvent.localContent
    return event.localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
}

/**
 * Check if event supports HTML rendering
 */
fun supportsHtmlRendering(event: TimelineEvent): Boolean {
    val content = event.content ?: event.decrypted ?: return false
    
    // Check if format is org.matrix.custom.html
    val format = content.optString("format", "")
    if (format != "org.matrix.custom.html") {
        return false
    }
    
    // Check if msgtype is supported
    val msgType = content.optString("msgtype", "")
    val supportedTypes = setOf(
        "m.text", "m.emote", "m.notice", 
        "m.image", "m.file", "m.audio", "m.video"
    )
    
    return msgType in supportedTypes && extractSanitizedHtml(event) != null
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
    color: Color = Color.Unspecified
) {
    val sanitizedHtml = remember(event) { extractSanitizedHtml(event) }
    
    if (sanitizedHtml == null) {
        // Fallback to plain text body
        val content = event.content ?: event.decrypted
        val body = content?.optString("body", "") ?: ""
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
    val annotatedString = remember(nodes) {
        try {
            inlineImages.clear()
            HtmlRenderer.render(
                nodes, 
                SpanStyle(color = color),
                inlineImages
            )
        } catch (e: Exception) {
            Log.e("Andromuks", "HtmlMessageText: Failed to render HTML", e)
            AnnotatedString("")
        }
    }
    
    // Create inline content map for images
    val inlineContentMap = remember(inlineImages) {
        inlineImages.mapValues { (_, imageData) ->
            InlineTextContent(
                Placeholder(
                    width = imageData.height.sp,
                    height = imageData.height.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                // Render the inline image
                InlineImage(
                    src = imageData.src,
                    alt = imageData.alt,
                    height = imageData.height,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken
                )
            }
        }
    }
    
    if (annotatedString.text.isEmpty()) {
        // Fallback if rendering failed
        val content = event.content ?: event.decrypted
        val body = content?.optString("body", "") ?: ""
        Text(
            text = body,
            modifier = modifier,
            color = color
        )
    } else {
        Text(
            text = annotatedString,
            modifier = modifier,
            inlineContent = inlineContentMap
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
            modifier = Modifier.size(height.dp)
        )
    } else {
        // Fallback to alt text
        Text(text = alt, fontSize = (height * 0.6).sp)
    }
}

