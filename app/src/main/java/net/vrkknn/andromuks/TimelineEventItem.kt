package net.vrkknn.andromuks

import android.util.Log
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.utils.AnimatedInlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.EmoteEventNarrator
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.extractSanitizedHtml
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.utils.MessageBubbleWithMenu
import net.vrkknn.andromuks.utils.MessageMenuConfig
import net.vrkknn.andromuks.utils.ReactionBadges
import net.vrkknn.andromuks.utils.ReplyPreview
import net.vrkknn.andromuks.utils.StickerMessage
import net.vrkknn.andromuks.utils.SystemEventNarrator
import net.vrkknn.andromuks.utils.extractStickerFromEvent
import net.vrkknn.andromuks.utils.supportsHtmlRendering
import net.vrkknn.andromuks.utils.RedactionUtils
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.utils.mediaBubbleColorFor
import net.vrkknn.andromuks.utils.stickerBubbleColorFor
import net.vrkknn.andromuks.utils.BubblePalette
import net.vrkknn.andromuks.utils.BubbleColors
import net.vrkknn.andromuks.BuildConfig

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReadReceipt
import net.vrkknn.andromuks.VersionedMessage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.utils.EditHistoryDialog
import androidx.compose.ui.tooling.preview.Preview
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.wrapContentWidth
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.RoomMemberCache

private val AvatarGap = 4.dp
private val AvatarColumnWidth = 32.dp // Wider column to prevent timestamp wrapping
private val AvatarPlaceholderWidth = AvatarColumnWidth + AvatarGap
private val ReadReceiptGap = 4.dp
private val HorizontalRuleHtmlRegex = Regex("^<\\s*hr\\s*/?\\s*>$", RegexOption.IGNORE_CASE)

private fun isHorizontalRuleHtml(html: String?): Boolean {
    val trimmed = html?.trim().orEmpty()
    return trimmed.isNotEmpty() && HorizontalRuleHtmlRegex.matches(trimmed)
}

private fun isHorizontalRuleMessage(
    event: TimelineEvent,
    content: JSONObject?,
    msgType: String
): Boolean {
    if (msgType != "m.text" && msgType != "m.notice") return false
    if (isHorizontalRuleHtml(extractSanitizedHtml(event))) return true
    return isHorizontalRuleHtml(content?.optString("formatted_body"))
}

/** Check if a message mentions a specific user */
private fun isMentioningUser(event: TimelineEvent, userId: String?): Boolean {
    if (userId == null) return false
    
    // Check in content (for unencrypted messages)
    val contentMentions = event.content?.optJSONObject("m.mentions")
    if (contentMentions != null) {
        val userIds = contentMentions.optJSONArray("user_ids")
        if (userIds != null) {
            for (i in 0 until userIds.length()) {
                if (userIds.optString(i) == userId) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "isMentioningUser: Found mention of $userId in event ${event.eventId}")
                    return true
                }
            }
        }
    }
    
    // Check in decrypted content (for encrypted messages)
    val decryptedMentions = event.decrypted?.optJSONObject("m.mentions")
    if (decryptedMentions != null) {
        val userIds = decryptedMentions.optJSONArray("user_ids")
        if (userIds != null) {
            for (i in 0 until userIds.length()) {
                if (userIds.optString(i) == userId) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "isMentioningUser: Found mention of $userId in encrypted event ${event.eventId}")
                    return true
                }
            }
        }
    }
    
    return false
}

fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(date)
}

/**
 * Check if a message body contains only an emoji (real emoji or custom emoji).
 * Real emojis are Unicode emoji characters.
 * Custom emojis can be:
 * - Markdown format: ![:name:](mxc://...)
 * - Shortcode format: :name: (which gets rendered as <img> in HTML)
 * 
 * This function handles both plain text and HTML content by stripping HTML tags.
 */
private val HtmlTagRegex = Regex("""<[^>]+>""")
private val CustomEmojiMarkdownRegex = Regex("""^!\[:([^\]]+):\]\(mxc://[^)]+\)$""")
private val CustomEmojiShortcodeRegex = Regex("""^:[\w+-]+:\s*$""")
// This regex matches emoji characters including variations and modifiers
private val EmojiRegex = try {
    Regex("""^[\p{Emoji}\p{Emoji_Presentation}\p{Emoji_Modifier_Base}\p{Emoji_Modifier}\p{Emoji_Component}\s]*$""")
} catch (e: Exception) {
    null
}
private val WhitespaceRegex = Regex("""\s+""")
private val AsciiLetterOrDigitRegex = Regex("""[a-zA-Z0-9]""")
private val CustomEmojiImgRegex = Regex("""^\s*<img[^>]*>\s*$""", RegexOption.IGNORE_CASE)
private val MxcUrlRegex = Regex("""src\s*=\s*["']mxc://[^"']+["']""", RegexOption.IGNORE_CASE)

/**
 * Check if a message body contains only an emoji (real emoji or custom emoji).
 * Real emojis are Unicode emoji characters.
 * Custom emojis can be:
 * - Markdown format: ![:name:](mxc://...)
 * - Shortcode format: :name: (which gets rendered as <img> in HTML)
 * 
 * This function handles both plain text and HTML content by stripping HTML tags.
 */
fun isEmojiOnlyMessage(body: String): Boolean {
    if (body.isBlank()) return false
    
    // Strip HTML tags if present (for HTML-formatted messages)
    val textWithoutHtml = body.replace(HtmlTagRegex, "").trim()
    if (textWithoutHtml.isEmpty()) return false
    
    // Remove whitespace (optimization: check empty before regex)
    val trimmed = textWithoutHtml.trim()
    if (trimmed.isEmpty()) return false
    
    // Check for custom emoji markdown pattern: ![:name:](mxc://...)
    if (CustomEmojiMarkdownRegex.matches(trimmed)) {
        return true
    }
    
    // Check for custom emoji shortcode pattern: :name: (with optional whitespace)
    // This is the format used in plain text body for custom emojis
    if (CustomEmojiShortcodeRegex.matches(trimmed)) {
        return true
    }
    
    // Check if the text contains only emoji characters
    // Wrap in try-catch for preview environments that don't support Unicode property classes
    
    // Remove all whitespace and check if only emoji remains
    val withoutWhitespace = trimmed.replace(WhitespaceRegex, "")
    if (withoutWhitespace.isEmpty()) return false
    
    // Check if all remaining characters are emojis
    // If emojiRegex is null (preview mode), skip the regex check and rely on other validations
    if (EmojiRegex != null && !EmojiRegex.matches(withoutWhitespace)) return false
    
    // CRITICAL FIX: Only treat as emoji-only if it's a SINGLE emoji (not multiple emojis or regular text)
    // The emoji regex correctly identifies emoji-only strings, but we need to:
    // 1. Ensure it's a single emoji (reasonable code point count: 1-8 for single emoji with modifiers)
    // 2. Exclude ASCII letters and digits to prevent regular text like "a" or "1" from being treated as emoji
    
    val codePointCount = withoutWhitespace.codePointCount(0, withoutWhitespace.length)
    
    // Single emojis are typically 1-2 code points, but with modifiers can be up to 4-5
    // Complex emoji sequences (like family emojis) can be longer, but those are still "single emoji" visually
    // We'll use a conservative limit of 8 code points to handle most single emoji cases
    if (codePointCount < 1 || codePointCount > 8) return false
    
    // CRITICAL: Exclude ASCII letters and digits to prevent regular text from being treated as emoji
    // Regular characters like "a", "1", etc. should never be treated as emoji-only messages
    val containsAsciiLetterOrDigit = AsciiLetterOrDigitRegex.containsMatchIn(withoutWhitespace)
    if (containsAsciiLetterOrDigit) return false
    
    // If we passed all checks, it's a valid single emoji
    return true
}

/**
 * Check if an HTML formatted body contains only a custom emoji image.
 * Custom emojis in HTML are rendered as: <img src="mxc://..." alt=":name:" ...>
 */
fun isCustomEmojiOnlyHtml(formattedBody: String?): Boolean {
    if (formattedBody.isNullOrBlank()) return false
    
    val trimmed = formattedBody.trim()
    
    // Check if the HTML contains only an img tag with MXC URL (custom emoji)
    // Pattern: <img ... src="mxc://..." ...> with optional whitespace
    // The src attribute can appear anywhere in the tag
    if (CustomEmojiImgRegex.matches(trimmed)) {
        // Verify it has an MXC URL in the src attribute
        if (MxcUrlRegex.containsMatchIn(trimmed)) {
            return true
        }
    }
    
    return false
}

@Composable
fun InlineBubbleTimestamp(
    timestamp: Long,
    editedBy: TimelineEvent? = null,
    isMine: Boolean,
    isConsecutive: Boolean,
    onEditedClick: (() -> Unit)? = null
) {
    // Only show timestamp inside bubble for consecutive messages
    if (isConsecutive) {
        val text =
                if (editedBy != null) {
                    " ${formatTimestamp(timestamp)} (edited)"
                } else {
                    " ${formatTimestamp(timestamp)}"
            }
        val modifier = if (editedBy != null && onEditedClick != null) {
            Modifier.clickable { onEditedClick() }
        } else {
            Modifier
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = modifier
        )
    }
}

/**
 * Smart message text renderer that automatically chooses between HTML and plain text based on
 * what's available in the event
 */
@Composable
fun AdaptiveMessageText(
    event: TimelineEvent,
    body: String,
    format: String?,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    roomId: String,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    onUserClick: (String) -> Unit = {},
    onMatrixUserClick: (String) -> Unit = onUserClick,
    onRoomLinkClick: (RoomLink) -> Unit = {},
    onCodeBlockClick: (String) -> Unit = {}
) {
    // For redacted messages, always use plain text to show the deletion message
    val isRedacted = event.redactedBy != null
    
    // Check if this is an emoji-only message
    // For HTML messages, also check the formatted_body for custom emoji images
    val formattedBody = event.content?.optString("formatted_body") 
        ?: event.decrypted?.optString("formatted_body")
        ?: event.localContent?.optString("sanitized_html")
    
    val isEmojiOnly = remember(body, formattedBody) { 
        isEmojiOnlyMessage(body) || (formattedBody != null && isCustomEmojiOnlyHtml(formattedBody))
    }
    
    // Check if HTML rendering is supported and available (and not redacted)
    // Support HTML if either the original event or the format parameter indicates HTML
    val supportsHtml = !isRedacted && (supportsHtmlRendering(event) || format == "org.matrix.custom.html")
    if (supportsHtml) {
        // CRITICAL FIX: Check if sanitized_html exists - if it does, always pass null to use it
        // sanitized_html has proper anchor tags, while formatted_body may not
        val hasSanitizedHtml = event.localContent?.optString("sanitized_html")?.isNotBlank() == true
        
        // Only pass htmlContent if:
        // 1. sanitized_html does NOT exist (so we need to use formatted_body or edit content)
        // 2. AND body contains HTML tags (indicating it's from an edit with HTML format)
        val htmlContent = if (!hasSanitizedHtml && format == "org.matrix.custom.html" && body.contains("<") && body.contains(">")) {
            // No sanitized_html, and body contains HTML tags - likely from an edit, pass it
            body
        } else {
            // Either sanitized_html exists (prefer it) or body is plain text - let HtmlMessageText extract from event
            null
        }
        HtmlMessageText(
            event = event,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            color = textColor,
            modifier = modifier,
            onMatrixUserClick = onMatrixUserClick,
            onRoomLinkClick = onRoomLinkClick,
            appViewModel = appViewModel,
            isEmojiOnly = isEmojiOnly,
            htmlContent = htmlContent, // Pass HTML edit content if available, otherwise null to extract from event
            onCodeBlockClick = onCodeBlockClick
        )
    } else {
        // Fallback to plain text for redacted messages or when HTML is not available
        // Apply 2x font size for emoji-only messages
        val baseStyle = MaterialTheme.typography.bodyMedium
        val textStyle = if (isEmojiOnly) {
            baseStyle.copy(fontSize = baseStyle.fontSize * 2)
        } else {
            baseStyle
        }
        
        // Show deletion message (in body parameter) or regular text
        Text(
            text = body,
            style = textStyle,
            color = textColor,
            fontStyle = if (isRedacted) FontStyle.Italic else FontStyle.Normal,
            modifier = modifier
        )
    }
}

@Composable
private fun HorizontalRuleMessage(
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    )
}

/**
 * Helper composable to render media messages with proper positioning.
 * Extracted to reduce complexity of TimelineEventItem function.
 */
@Composable
private fun MediaMessageItem(
    mediaMessage: MediaMessage,
    replyInfo: ReplyInfo?,
    originalEvent: TimelineEvent?,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    hasEncryptedFile: Boolean,
    event: TimelineEvent,
    isConsecutive: Boolean,
    editedBy: TimelineEvent?,
    timelineEvents: List<TimelineEvent>,
    onScrollToMessage: (String) -> Unit,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUserClick: (String) -> Unit,
    appViewModel: AppViewModel? = null,
    myUserId: String? = null,
    powerLevels: PowerLevelsInfo? = null,
    onBubbleClick: (() -> Unit)? = null,
    onShowEditHistory: (() -> Unit)? = null,
    precomputedHasBeenEdited: Boolean? = null
) {
    // Check if this is a thread message
    val isThreadMessage = event.isThreadMessage()
    val colorScheme = MaterialTheme.colorScheme
    val mediaHasBeenEdited =
        precomputedHasBeenEdited ?: remember(event.eventId, appViewModel?.timelineUpdateCounter) {
            appViewModel?.isMessageEdited(event.eventId) ?: false
        }
    val mediaBubbleColor = remember(mediaHasBeenEdited, isThreadMessage, isMine, colorScheme) {
        mediaBubbleColorFor(
            colorScheme = colorScheme,
            isMine = isMine,
            isThreadMessage = isThreadMessage,
            hasBeenEdited = mediaHasBeenEdited
        )
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Show nested reply preview (also for thread messages so replies render consistently)
        if (replyInfo != null && originalEvent != null) {
            Column {
                val replyPreviewColors = rememberReplyPreviewColors(
                    colorScheme = colorScheme,
                    replyInfo = replyInfo,
                    originalEvent = originalEvent,
                    myUserId = myUserId,
                    appViewModel = appViewModel
                )
                ReplyPreview(
                    replyInfo = replyInfo,
                    originalEvent = originalEvent,
                    userProfileCache = userProfileCache,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    previewColors = replyPreviewColors,
                    modifier = Modifier.padding(bottom = 8.dp),
                    onOriginalMessageClick = {
                        onScrollToMessage(replyInfo.eventId)
                    },
                    timelineEvents = timelineEvents,
                    onMatrixUserClick = onUserClick,
                    appViewModel = appViewModel
                )
                MediaMessage(
                    mediaMessage = mediaMessage,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    isMine = isMine,
                    isEncrypted = hasEncryptedFile,
                    event = event,
                    timestamp = event.timestamp,
                    isConsecutive = isConsecutive,
                    editedBy = editedBy,
                    onReply = onReply,
                    onReact = onReact,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onUserClick = onUserClick,
                    myUserId = myUserId,
                    powerLevels = powerLevels,
                    appViewModel = appViewModel,
                    onBubbleClick = onBubbleClick,
                    onShowEditHistory = onShowEditHistory,
                    bubbleColorOverride = mediaBubbleColor,
                    hasBeenEditedOverride = mediaHasBeenEdited
                )
            }
        } else {
            MediaMessage(
                mediaMessage = mediaMessage,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isMine = isMine,
                isEncrypted = hasEncryptedFile,
                event = event,
                timestamp = event.timestamp,
                isConsecutive = isConsecutive,
                editedBy = editedBy,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                onUserClick = onUserClick,
                myUserId = myUserId,
                powerLevels = powerLevels,
                appViewModel = appViewModel,
                onBubbleClick = onBubbleClick,
                bubbleColorOverride = mediaBubbleColor,
                hasBeenEditedOverride = mediaHasBeenEdited
            )
        }
    }
}

@Composable
private fun MessageTypeContent(
    event: TimelineEvent,
    timelineEvents: List<TimelineEvent>,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>,
    actualIsMine: Boolean,
    myUserId: String?,
    isConsecutive: Boolean,
    displayName: String?,
    avatarUrl: String?,
    mentionsMe: Boolean,
    readReceipts: List<ReadReceipt>,
    editedBy: TimelineEvent?,
    appViewModel: AppViewModel?,
    onScrollToMessage: (String) -> Unit,
    onReply: (TimelineEvent) -> Unit,
    onReact: (TimelineEvent) -> Unit,
    onEdit: (TimelineEvent) -> Unit,
    onDelete: (TimelineEvent) -> Unit,
    onUserClick: (String) -> Unit,
    onRoomLinkClick: (RoomLink) -> Unit,
    onThreadClick: (TimelineEvent) -> Unit,
    onShowEditHistory: (() -> Unit)? = null,
    onCodeBlockClick: (String) -> Unit = {},
    onShowMenu: ((MessageMenuConfig) -> Unit)? = null
) {
    when (event.type) {
        "m.room.redaction" -> {
            // Handle redaction events - these should not be displayed as regular messages
            // The redaction logic will be handled by modifying the original message
            // When a message is redacted, it gets a redactedBy field pointing to the
            // redaction event
            // We use this to display deletion messages instead of the original content
            return
        }
        "m.room.message" -> {
            RoomMessageContent(
                event = event,
                timelineEvents = timelineEvents,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                userProfileCache = userProfileCache,
                actualIsMine = actualIsMine,
                myUserId = myUserId,
                isConsecutive = isConsecutive,
                displayName = displayName,
                avatarUrl = avatarUrl,
                mentionsMe = mentionsMe,
                readReceipts = readReceipts,
                editedBy = editedBy,
                appViewModel = appViewModel,
                onScrollToMessage = onScrollToMessage,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                onUserClick = onUserClick,
                onRoomLinkClick = onRoomLinkClick,
                onThreadClick = onThreadClick,
                onShowEditHistory = onShowEditHistory,
                onCodeBlockClick = onCodeBlockClick,
                onShowMenu = onShowMenu
            )
        }
        "m.room.encrypted" -> {
            EncryptedMessageContent(
                event = event,
                timelineEvents = timelineEvents,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                userProfileCache = userProfileCache,
                actualIsMine = actualIsMine,
                myUserId = myUserId,
                isConsecutive = isConsecutive,
                displayName = displayName,
                avatarUrl = avatarUrl,
                mentionsMe = mentionsMe,
                readReceipts = readReceipts,
                editedBy = editedBy,
                appViewModel = appViewModel,
                onScrollToMessage = onScrollToMessage,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                onUserClick = onUserClick,
                onRoomLinkClick = onRoomLinkClick,
                onThreadClick = onThreadClick,
                onShowEditHistory = onShowEditHistory,
                onCodeBlockClick = onCodeBlockClick,
                onShowMenu = onShowMenu
            )
        }
        "m.sticker" -> {
            StickerMessageContent(
                event = event,
                actualIsMine = actualIsMine,
                readReceipts = readReceipts,
                userProfileCache = userProfileCache,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                appViewModel = appViewModel,
                onUserClick = onUserClick,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                myUserId = myUserId,
                isConsecutive = isConsecutive,
                onThreadClick = onThreadClick,
                onShowMenu = onShowMenu
            )
        }
        "m.reaction" -> {
            // Reaction events are processed by processReactionEvent and displayed as badges
            // on messages
            // No need to render them as separate timeline items
            return
        }
        else -> {
            Text(
                text = "Event type: ${event.type}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RoomMessageContent(
    event: TimelineEvent,
    timelineEvents: List<TimelineEvent>,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>,
    actualIsMine: Boolean,
    myUserId: String?,
    isConsecutive: Boolean,
    displayName: String?,
    avatarUrl: String?,
    mentionsMe: Boolean,
    readReceipts: List<ReadReceipt>,
    editedBy: TimelineEvent?,
    appViewModel: AppViewModel?,
    onScrollToMessage: (String) -> Unit,
    onReply: (TimelineEvent) -> Unit,
    onReact: (TimelineEvent) -> Unit,
    onEdit: (TimelineEvent) -> Unit,
    onDelete: (TimelineEvent) -> Unit,
    onUserClick: (String) -> Unit,
    onRoomLinkClick: (RoomLink) -> Unit,
    onThreadClick: (TimelineEvent) -> Unit,
    onShowEditHistory: (() -> Unit)? = null,
    onCodeBlockClick: (String) -> Unit = {},
    onShowMenu: ((MessageMenuConfig) -> Unit)? = null
) {
    // Check if this is an edit event (m.replace relationship)
    val isEditEvent =
        event.content?.optJSONObject("m.relates_to")?.optString("rel_type") ==
            "m.replace"
    // For edit events, get content from m.new_content; for regular messages, use
    // content directly
    // This ensures edit events display the new content instead of the edit metadata
    val content =
        if (isEditEvent) {
            event.content?.optJSONObject("m.new_content")
        } else {
            event.content
        }
    val format = content?.optString("format", "")
    val body =
        if (format == "org.matrix.custom.html") {
            content?.optString("formatted_body", "") ?: ""
        } else {
            content?.optString("body", "") ?: ""
        }
    val msgType = content?.optString("msgtype", "") ?: ""
    
    // If this message has been edited (by another event), use the latest edit content
    val (finalBody, finalFormat, finalMsgType) = if (editedBy != null && !isEditEvent) {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomMessageContent: Using edit content for event ${event.eventId}, edit event: ${editedBy.eventId}")
        val newContent = editedBy.content?.optJSONObject("m.new_content")
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "RoomMessageContent: newContent is null: ${newContent == null}")
            if (newContent != null) {
                Log.d("Andromuks", "RoomMessageContent: newContent keys: ${newContent.keys().asSequence().toList()}")
            }
        }
        val editFormatRaw = newContent?.optString("format", "") ?: ""
        val editFormat = if (editFormatRaw.isBlank()) format else editFormatRaw
        val editMsgType = newContent?.optString("msgtype", "") ?: msgType
        
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "RoomMessageContent: editFormatRaw: '$editFormatRaw', editFormat: '$editFormat', original format: '$format'")
        }
        
        // For HTML messages, prefer sanitized_html from localContent if available, otherwise formatted_body
        val editBody = if (editFormat == "org.matrix.custom.html") {
            // Check localContent first (sanitized_html), then formatted_body, then body
            val sanitized = editedBy.localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
            val formatted = newContent?.optString("formatted_body", "")?.takeIf { it.isNotBlank() }
            val result = sanitized ?: formatted ?: body
            if (BuildConfig.DEBUG) {
                Log.d("Andromuks", "RoomMessageContent: Edit HTML - sanitized_html present: ${sanitized != null}, formatted_body present: ${formatted != null}, result length: ${result.length}, preview: ${result.take(100)}")
            }
            result
        } else {
            val result = newContent?.optString("body", "") ?: body
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomMessageContent: Edit plain text - body length: ${result.length}, preview: ${result.take(50)}")
            result
        }
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "RoomMessageContent: Final edit result - body length: ${editBody.length}, format: $editFormat, msgType: $editMsgType")
        }
        Triple(editBody, editFormat, editMsgType)
    } else {
        if (BuildConfig.DEBUG) {
            if (editedBy == null) {
                Log.d("Andromuks", "RoomMessageContent: No edit found for event ${event.eventId}, using original body length: ${body.length}")
            } else if (isEditEvent) {
                Log.d("Andromuks", "RoomMessageContent: Event ${event.eventId} IS an edit event, using original body")
            }
        }
        Triple(body, format, msgType)
    }
    
    // Handle m.emote messages with narrator rendering
    if (finalMsgType == "m.emote") {
        Column(modifier = Modifier.fillMaxWidth()) {
            EmoteEventNarrator(
                event = event,
                displayName = displayName ?: event.sender,
                avatarUrl = avatarUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                onReply = { onReply(event) },
                onReact = { onReact(event) },
                onEdit = { onEdit(event) },
                onDelete = { onDelete(event) },
                onUserClick = onUserClick
            )
            
            // Add reaction badges for emote messages
            if (appViewModel != null) {
                val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                    appViewModel.messageReactions[event.eventId] ?: emptyList()
                }
                if (reactions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        ReactionBadges(
                            eventId = event.eventId,
                            reactions = reactions,
                            homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isMine = actualIsMine,
                            onReactionClick = { emoji ->
                                appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                            }
                        )
                    }
                }
            }
        }
        return
    }

    // OPTIMIZED: Check if this message has been redacted using O(1) lookup
    val isRedacted = event.redactedBy != null
    val redactionEvent = if (isRedacted && appViewModel != null) {
        appViewModel.getRedactionEvent(event.eventId)  // O(1) lookup!
    } else null
    
    // Fallback: If cache lookup failed (e.g., for pagination events), search timeline events
    val finalRedactionEvent = redactionEvent ?: if (isRedacted) {
        @Suppress("DEPRECATION")
        net.vrkknn.andromuks.utils.RedactionUtils.findLatestRedactionEvent(event.eventId, timelineEvents)
    } else null
    
    val redactionSender = finalRedactionEvent?.sender

    // Request profile if redaction sender is missing from cache
    if (isRedacted && redactionSender != null && appViewModel != null) {
        if (!userProfileCache.containsKey(redactionSender)) {
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "RoomTimelineScreen: Requesting profile for redaction sender: $redactionSender in room ${event.roomId}"
            )
            appViewModel.requestUserProfile(redactionSender, event.roomId)
        }
    }

    // Show deletion message if redacted, otherwise show the message content
    // Use finalBody which already includes edit content if the message was edited
    val displayBody =
        if (isRedacted) {
            // Create deletion message using cached redaction event (or fallback to timeline search)
            net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                finalRedactionEvent,
                userProfileCache
            )
        } else {
            finalBody // Show the message content (includes edit content if edited)
        }

    // Check if message has been edited (O(1) lookup) - placed early so downstream renderers can use it
    val hasBeenEdited = remember(event.eventId, appViewModel?.timelineUpdateCounter) {
        appViewModel?.isMessageEdited(event.eventId) ?: false
    }

    val isHorizontalRule = !isRedacted && isHorizontalRuleMessage(event, content, msgType)
    if (isHorizontalRule) {
        HorizontalRuleMessage()
        return
    }

    // Check if this is a reply message
    val replyInfo = event.getReplyInfo()
    val originalEvent = rememberReplyTargetEvent(
        replyInfo = replyInfo,
        timelineEvents = timelineEvents,
        roomId = event.roomId,
        appViewModel = appViewModel
    )

    // Check if it's a sticker message (sent via send_message with base_content)
    if (msgType == "m.sticker") {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "TimelineEventItem: Detected m.sticker message in m.room.message event ${event.eventId}, isConsecutive=$isConsecutive")
        val stickerMessage = extractStickerFromEvent(event)
        val isRedacted = event.redactedBy != null
        val redactionEvent = if (isRedacted && appViewModel != null) appViewModel.getRedactionEvent(event.eventId) else null
        
        // Fallback: If cache lookup failed (e.g., for pagination events), search timeline events
        val finalRedactionEvent = redactionEvent ?: if (isRedacted) {
            @Suppress("DEPRECATION")
            net.vrkknn.andromuks.utils.RedactionUtils.findLatestRedactionEvent(event.eventId, timelineEvents)
        } else null

        if (isRedacted) {
            val deletionMessage =
                net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                    finalRedactionEvent,
                    userProfileCache
                )

            val bubbleShape =
                if (actualIsMine) {
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 2.dp,
                        bottomEnd = 12.dp,
                        bottomStart = 12.dp
                    )
                } else {
                    RoundedCornerShape(
                        topStart = 2.dp,
                        topEnd = 12.dp,
                        bottomEnd = 12.dp,
                        bottomStart = 12.dp
                    )
                }

            val deletionColors = BubblePalette.colors(
                colorScheme = MaterialTheme.colorScheme,
                isMine = actualIsMine,
                isRedacted = true
            )
            val bubbleColor = deletionColors.container
            val textColor = deletionColors.content
            val isDarkMode = isSystemInDarkTheme()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    if (actualIsMine) Arrangement.End else Arrangement.Start
            ) {
                MessageBubbleWithMenu(
                    event = event,
                    bubbleColor = bubbleColor,
                    bubbleShape = bubbleShape,
                    modifier = Modifier
                        .padding(top = 4.dp),
                    isMine = actualIsMine,
                    myUserId = myUserId,
                    powerLevels = appViewModel?.currentRoomState?.powerLevels,
                    onReply = { onReply(event) },
                    onReact = { onReact(event) },
                    onEdit = { onEdit(event) },
                    onDelete = { onDelete(event) },
                    appViewModel = appViewModel,
                    onBubbleClick = { onThreadClick(event) },
                    onShowEditHistory = null,
                onShowMenu = onShowMenu,
                    mentionBorder = deletionColors.mentionBorder
                ) {
                    Text(
                        text = deletionMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            return
        }

        if (stickerMessage != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "TimelineEventItem: Successfully extracted sticker, rendering StickerMessageContent with isConsecutive=$isConsecutive")
            StickerMessageContent(
                event = event,
                actualIsMine = actualIsMine,
                readReceipts = readReceipts,
                userProfileCache = userProfileCache,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                appViewModel = appViewModel,
                onUserClick = onUserClick,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                myUserId = myUserId,
                isConsecutive = isConsecutive,
                onThreadClick = onThreadClick
            )
        } else {
            // Fallback: show as text if sticker extraction fails
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "TimelineEventItem: Failed to extract sticker from m.room.message with msgtype m.sticker")
        }
        return
    }
    
    // Check if it's a media message
    if (msgType == "m.image" || msgType == "m.video" || msgType == "m.audio" || msgType == "m.file") {
        RoomMediaMessageContent(
            event = event,
            content = content,
            body = body,
            msgType = msgType,
            actualIsMine = actualIsMine,
            mentionsMe = mentionsMe,
            isRedacted = isRedacted,
            redactionEvent = redactionEvent,
            userProfileCache = userProfileCache,
            replyInfo = replyInfo,
            originalEvent = originalEvent,
            timelineEvents = timelineEvents,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            isConsecutive = isConsecutive,
            editedBy = editedBy,
            appViewModel = appViewModel,
            myUserId = myUserId,
            onScrollToMessage = onScrollToMessage,
            onReply = onReply,
            onReact = onReact,
            onEdit = onEdit,
            onDelete = onDelete,
            onUserClick = onUserClick,
            onThreadClick = onThreadClick,
            onShowEditHistory = onShowEditHistory,
            onShowMenu = onShowMenu
        )
    } else {
        RoomTextMessageContent(
            event = event,
            finalBody = displayBody,
            format = finalFormat,
            actualIsMine = actualIsMine,
            mentionsMe = mentionsMe,
            readReceipts = readReceipts,
            userProfileCache = userProfileCache,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            replyInfo = replyInfo,
            originalEvent = originalEvent,
            timelineEvents = timelineEvents,
            isConsecutive = isConsecutive,
            editedBy = editedBy,
            appViewModel = appViewModel,
            myUserId = myUserId,
            hasBeenEdited = hasBeenEdited,
            onScrollToMessage = onScrollToMessage,
            onReply = onReply,
            onReact = onReact,
            onEdit = onEdit,
            onDelete = onDelete,
            onUserClick = onUserClick,
            onRoomLinkClick = onRoomLinkClick,
            onThreadClick = onThreadClick,
            onShowEditHistory = onShowEditHistory,
            onCodeBlockClick = onCodeBlockClick,
            onShowMenu = onShowMenu
        )
    }
}

@Composable
private fun RoomMediaMessageContent(
    event: TimelineEvent,
    content: org.json.JSONObject?,
    body: String,
    msgType: String,
    actualIsMine: Boolean,
    mentionsMe: Boolean,
    isRedacted: Boolean,
    redactionEvent: TimelineEvent?,
    userProfileCache: Map<String, MemberProfile>,
    replyInfo: ReplyInfo?,
    originalEvent: TimelineEvent?,
    timelineEvents: List<TimelineEvent>,
    homeserverUrl: String,
    authToken: String,
    isConsecutive: Boolean,
    editedBy: TimelineEvent?,
    appViewModel: AppViewModel?,
    myUserId: String?,
    onScrollToMessage: (String) -> Unit,
    onReply: (TimelineEvent) -> Unit,
    onReact: (TimelineEvent) -> Unit,
    onEdit: (TimelineEvent) -> Unit,
    onDelete: (TimelineEvent) -> Unit,
    onUserClick: (String) -> Unit,
    onThreadClick: (TimelineEvent) -> Unit,
    onShowEditHistory: (() -> Unit)? = null,
    onShowMenu: ((MessageMenuConfig) -> Unit)? = null
) {
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "TimelineEventItem: Found media message - msgType=$msgType, body=$body"
    )

    val colorScheme = MaterialTheme.colorScheme
    val mediaHasBeenEdited = remember(event.eventId, appViewModel?.timelineUpdateCounter) {
        appViewModel?.isMessageEdited(event.eventId) ?: false
    }

    // If media message is redacted, show deletion message instead of media
    if (isRedacted) {
        // Fallback: If cache lookup failed (e.g., for pagination events), search timeline events
        val finalRedactionEvent = redactionEvent ?: run {
            @Suppress("DEPRECATION")
            net.vrkknn.andromuks.utils.RedactionUtils.findLatestRedactionEvent(event.eventId, timelineEvents)
        }
        
        // Display deletion message for media using cached redaction event (or fallback to timeline search)
        val deletionMessage =
            net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                finalRedactionEvent,
                userProfileCache
            )

        val bubbleShape =
            if (actualIsMine) {
                RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 2.dp,
                    bottomEnd = 12.dp,
                    bottomStart = 12.dp
                )
            } else {
                RoundedCornerShape(
                    topStart = 2.dp,
                    topEnd = 12.dp,
                    bottomEnd = 12.dp,
                    bottomStart = 12.dp
                )
            }

        val deletionColors = BubblePalette.colors(
            colorScheme = MaterialTheme.colorScheme,
            isMine = actualIsMine,
            isRedacted = true
        )
        val bubbleColor = deletionColors.container
        val textColor = deletionColors.content

        // Detect dark mode for custom shadow/glow
        val isDarkMode = isSystemInDarkTheme()
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (actualIsMine) Arrangement.End else Arrangement.Start
        ) {
            MessageBubbleWithMenu(
                event = event,
                bubbleColor = bubbleColor,
                bubbleShape = bubbleShape,
                modifier = Modifier
                    .padding(top = 4.dp),
                isMine = actualIsMine,
                myUserId = myUserId,
                powerLevels = appViewModel?.currentRoomState?.powerLevels,
                onReply = { onReply(event) },
                onReact = { onReact(event) },
                onEdit = { onEdit(event) },
                onDelete = { onDelete(event) },
                appViewModel = appViewModel,
                onBubbleClick = { onThreadClick(event) },
                onShowEditHistory = null,
                onShowMenu = onShowMenu,
                mentionBorder = deletionColors.mentionBorder
            ) {
                Text(
                    text = deletionMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    fontStyle = FontStyle.Italic, // Make deletion messages italic
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        return // Exit early for redacted media messages
    }

    // Check if media is encrypted (has file object) or unencrypted (has url field)
    val fileObj = content?.optJSONObject("file")
    val hasEncryptedFile = fileObj != null
    val directUrl = content?.optString("url", "") ?: ""
    val fileUrl = fileObj?.optString("url", "") ?: ""
    val url = directUrl.takeIf { it.isNotBlank() } ?: fileUrl

    val filename = content?.optString("filename", "") ?: ""
    val info = content?.optJSONObject("info")

    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "TimelineEventItem: Media data - url=$url, filename=$filename, info=${info != null}, hasEncryptedFile=$hasEncryptedFile"
    )

    if (url.isNotBlank() && info != null) {
        // Media parsing and display logic would go here
        // This is a large section that I'll extract from the original code
        val width = info.optInt("w", 0)
        val height = info.optInt("h", 0)
        val size = info.optLong("size", 0)
        val mimeType = info.optString("mimetype", "")
        val blurHash =
            info.optString("xyz.amorgan.blurhash")
                .takeIf { it.isNotBlank() }
                ?: info.optString("blurhash").takeIf { it.isNotBlank() }

        // Extract thumbnail info (images and videos)
        var thumbnailIsEncrypted = false
        val thumbnailFile = info.optJSONObject("thumbnail_file")
        val thumbnailUrl = when {
            thumbnailFile != null -> {
                thumbnailIsEncrypted = true
                thumbnailFile.optString("url", "")?.takeIf { it.isNotBlank() }
            }
            else -> info.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
        }

        val thumbnailInfo = info.optJSONObject("thumbnail_info")
        
        val thumbnailBlurHash =
            thumbnailInfo
                ?.optString("xyz.amorgan.blurhash")
                ?.takeIf { it.isNotBlank() }
                ?: thumbnailInfo?.optString("blurhash")?.takeIf { it.isNotBlank() }
        // CRITICAL FIX: Return nullable Int? and only populate when value exists and is > 0
        // Without this, thumbnailWidth/Height are 0 instead of null, breaking size calculations
        val thumbnailWidth = thumbnailInfo?.optInt("w", 0)?.takeIf { it > 0 }
        val thumbnailHeight = thumbnailInfo?.optInt("h", 0)?.takeIf { it > 0 }
        val duration = if (msgType == "m.video" || msgType == "m.audio") {
            info.optInt("duration", 0).takeIf { it > 0 }
        } else null

        // Extract is_animated from MSC4230 (for animated images: GIF, animated PNG, animated WebP)
        val isAnimated = if (msgType == "m.image") {
            info.optBoolean("is_animated", false).takeIf { info.has("is_animated") }
        } else null

        // Extract caption
        // Caption is only body if: 1) filename field exists, 2) body differs from filename, 3) body is not blank
        // If filename field is missing, body IS the filename, not a caption
        val caption = if (filename.isNotBlank() && body != filename && body.isNotBlank()) {
            val localContent = event.localContent
            val sanitizedHtml = localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
            if (sanitizedHtml != null && sanitizedHtml != filename) {
                sanitizedHtml
            } else {
                body
            }
        } else null

        val mediaInfo =
            MediaInfo(
                width = width,
                height = height,
                size = size,
                mimeType = mimeType,
                blurHash = blurHash,
                thumbnailUrl = thumbnailUrl,
                thumbnailBlurHash = thumbnailBlurHash,
                thumbnailWidth = thumbnailWidth,
                thumbnailHeight = thumbnailHeight,
                duration = duration,
                thumbnailIsEncrypted = thumbnailIsEncrypted,
                isAnimated = isAnimated
            )

        val mediaMessage =
            MediaMessage(
                url = url,
                filename = filename,
                caption = caption,
                info = mediaInfo,
                msgType = msgType
            )

        val mediaIsThreadMessage = event.isThreadMessage()
        val mediaBubbleColor = remember(mediaHasBeenEdited, mediaIsThreadMessage, actualIsMine, colorScheme) {
            mediaBubbleColorFor(
                colorScheme = colorScheme,
                isMine = actualIsMine,
                isThreadMessage = mediaIsThreadMessage,
                hasBeenEdited = mediaHasBeenEdited
            )
        }

        // Display media message with nested reply structure
        MediaMessageItem(
            mediaMessage = mediaMessage,
            replyInfo = replyInfo,
            originalEvent = originalEvent,
            userProfileCache = userProfileCache,
            homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
            authToken = authToken,
            isMine = actualIsMine,
            hasEncryptedFile = hasEncryptedFile,
            event = event,
            isConsecutive = isConsecutive,
            editedBy = editedBy,
            timelineEvents = timelineEvents,
            onScrollToMessage = onScrollToMessage,
            onReply = { onReply(event) },
            onReact = { onReact(event) },
            onEdit = { onEdit(event) },
            onDelete = { onDelete(event) },
            onUserClick = onUserClick,
            appViewModel = appViewModel,
            myUserId = myUserId,
            powerLevels = appViewModel?.currentRoomState?.powerLevels,
            onBubbleClick = if (event.isThreadMessage()) {
                { onThreadClick(event) }
            } else {
                null
            },
            onShowEditHistory = onShowEditHistory,
            precomputedHasBeenEdited = mediaHasBeenEdited
        )

        // Add reaction badges for media messages
        if (appViewModel != null) {
            val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                appViewModel.messageReactions[event.eventId] ?: emptyList()
            }
            if (reactions.isNotEmpty()) {
                val mediaReactionInset = 12.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .reactionHorizontalInset(actualIsMine, mediaReactionInset),
                    horizontalArrangement =
                        if (actualIsMine) Arrangement.End else Arrangement.Start
                ) {
                    ReactionBadges(
                        eventId = event.eventId,
                        reactions = reactions,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isMine = actualIsMine,
                        bubbleColor = mediaBubbleColor,
                        onReactionClick = { emoji ->
                            appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                        }
                    )
                }
            }
        }
    } else {
        // Fallback to text message if media parsing fails
        val bubbleShape =
            if (actualIsMine) {
                RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 2.dp,
                    bottomEnd = 8.dp,
                    bottomStart = 12.dp
                )
            } else {
                RoundedCornerShape(
                    topStart = 2.dp,
                    topEnd = 12.dp,
                    bottomEnd = 12.dp,
                    bottomStart = 8.dp
                )
            }
        
        val fallbackColors = BubblePalette.colors(
            colorScheme = colorScheme,
            isMine = actualIsMine,
            isEdited = mediaHasBeenEdited,
            mentionsMe = mentionsMe
        )
        val bubbleColor = fallbackColors.container
        val textColor = fallbackColors.content

        // Detect dark mode for custom shadow/glow
        val isDarkMode = isSystemInDarkTheme()
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                if (actualIsMine) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                color = bubbleColor,
                shape = bubbleShape,
                tonalElevation = 0.dp,  // No elevation/shadow
                shadowElevation = 0.dp,  // No shadow
                modifier = Modifier
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier =
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun RoomTextMessageContent(
    event: TimelineEvent,
    finalBody: String,
    format: String?,
    actualIsMine: Boolean,
    mentionsMe: Boolean,
    readReceipts: List<ReadReceipt>,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    replyInfo: ReplyInfo?,
    originalEvent: TimelineEvent?,
    timelineEvents: List<TimelineEvent>,
    isConsecutive: Boolean,
    editedBy: TimelineEvent?,
    appViewModel: AppViewModel?,
    myUserId: String?,
    hasBeenEdited: Boolean,
    onScrollToMessage: (String) -> Unit,
    onReply: (TimelineEvent) -> Unit,
    onReact: (TimelineEvent) -> Unit,
    onEdit: (TimelineEvent) -> Unit,
    onDelete: (TimelineEvent) -> Unit,
    onUserClick: (String) -> Unit,
    onRoomLinkClick: (RoomLink) -> Unit,
    onThreadClick: (TimelineEvent) -> Unit,
    onShowEditHistory: (() -> Unit)? = null,
    onCodeBlockClick: (String) -> Unit = {},
    onShowMenu: ((MessageMenuConfig) -> Unit)? = null
) {
    val bubbleShape =
        if (actualIsMine) {
            RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 2.dp,
                bottomEnd = 12.dp,
                bottomStart = 12.dp
            )
        } else {
            RoundedCornerShape(
                topStart = 2.dp,
                topEnd = 12.dp,
                bottomEnd = 12.dp,
                bottomStart = 12.dp
            )
        }
    
    // Check if this is a thread message
    val isThreadMessage = event.isThreadMessage()
    
    
    val containsSpoiler = event.containsSpoilerContent()
    val colorScheme = MaterialTheme.colorScheme
    val bubbleColors = remember(
        event.eventId,
        actualIsMine,
        hasBeenEdited,
        mentionsMe,
        isThreadMessage,
        containsSpoiler
    ) {
        BubblePalette.colors(
            colorScheme = colorScheme,
            isMine = actualIsMine,
            isEdited = hasBeenEdited,
            mentionsMe = mentionsMe,
            isThreadMessage = isThreadMessage,
            hasSpoiler = containsSpoiler
        )
    }
    val bubbleColor = bubbleColors.container
    val textColor = bubbleColors.content

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (actualIsMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // For my messages, show read receipts on the left of the bubble
        if (actualIsMine && readReceipts.isNotEmpty()) {
            AnimatedInlineReadReceiptAvatars(
                receipts = readReceipts,
                userProfileCache = userProfileCache,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                appViewModel = appViewModel,
                messageSender = event.sender,
                eventId = event.eventId,
                roomId = event.roomId,
                onUserClick = onUserClick,
                isMine = true
            )
            Spacer(modifier = Modifier.width(ReadReceiptGap))
        }

        // Display reply with nested structure if this is a reply (include thread messages too)
        if (replyInfo != null && originalEvent != null) {
            MessageBubbleWithMenu(
                event = event,
                bubbleColor = bubbleColor,
                bubbleShape = bubbleShape,
                modifier = Modifier.widthIn(max = 300.dp),
                isMine = actualIsMine,
                myUserId = myUserId,
                powerLevels = appViewModel?.currentRoomState?.powerLevels,
                onReply = { onReply(event) },
                onReact = { onReact(event) },
                onEdit = { onEdit(event) },
                onDelete = { onDelete(event) },
                appViewModel = appViewModel,
                onBubbleClick = if (isThreadMessage) {
                    { onThreadClick(event) }
                } else {
                    null
                },
                onShowEditHistory = if (hasBeenEdited) onShowEditHistory else null,
                mentionBorder = bubbleColors.mentionBorder,
                onShowMenu = onShowMenu
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    // Reply preview
                    val replyPreviewColors = rememberReplyPreviewColors(
                        colorScheme = colorScheme,
                        replyInfo = replyInfo,
                        originalEvent = originalEvent,
                        myUserId = myUserId,
                        appViewModel = appViewModel
                    )
                    ReplyPreview(
                        replyInfo = replyInfo,
                        originalEvent = originalEvent,
                        userProfileCache = userProfileCache,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        previewColors = replyPreviewColors,
                        modifier =
                            Modifier.padding(bottom = 6.dp)
                                .align(Alignment.Start),
                        onOriginalMessageClick = {
                            onScrollToMessage(replyInfo.eventId)
                        },
                        timelineEvents = timelineEvents,
                        onMatrixUserClick = onUserClick,
                        appViewModel = appViewModel
                    )

                    // Reply message content
                    val messageBody: @Composable () -> Unit = {
                            // Render text bubble with optional edit icon
                            val messageBody: @Composable () -> Unit = {
                                AdaptiveMessageText(
                                    event = event,
                                    body = finalBody,
                                    format = format,
                                    userProfileCache = userProfileCache,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    appViewModel = appViewModel,
                                    roomId = event.roomId,
                                    textColor = textColor,
                                    onUserClick = onUserClick,
                                    onMatrixUserClick = onUserClick,
                                    onRoomLinkClick = onRoomLinkClick,
                                    onCodeBlockClick = onCodeBlockClick
                                )
                            }
                            if (hasBeenEdited) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (actualIsMine) {
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = "Edited",
                                            tint = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        messageBody()
                                    } else {
                                        messageBody()
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = "Edited",
                                            tint = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            } else {
                                messageBody()
                            }
                    }
                    if (hasBeenEdited) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (actualIsMine) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edited",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                messageBody()
                            } else {
                                messageBody()
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edited",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    } else {
                        messageBody()
                    }
                }
            }
        } else {
            // Regular message bubble with popup menu
            MessageBubbleWithMenu(
                event = event,
                bubbleColor = bubbleColor,
                bubbleShape = bubbleShape,
                modifier = Modifier.widthIn(max = 300.dp),
                isMine = actualIsMine,
                myUserId = myUserId,
                powerLevels = appViewModel?.currentRoomState?.powerLevels,
                onReply = { onReply(event) },
                onReact = { onReact(event) },
                onEdit = { onEdit(event) },
                onDelete = { onDelete(event) },
                appViewModel = appViewModel,
                onBubbleClick = if (isThreadMessage) {
                    { onThreadClick(event) }
                } else {
                    null
                },
                onShowEditHistory = if (hasBeenEdited) onShowEditHistory else null,
                mentionBorder = bubbleColors.mentionBorder,
                onShowMenu = onShowMenu
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    // Text message content with optional edit icon
                    val messageBody: @Composable () -> Unit = {
                        AdaptiveMessageText(
                            event = event,
                            body = finalBody,
                            format = format,
                            userProfileCache = userProfileCache,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            appViewModel = appViewModel,
                            roomId = event.roomId,
                            textColor = textColor,
                            onUserClick = onUserClick,
                            onMatrixUserClick = onUserClick,
                            onRoomLinkClick = onRoomLinkClick,
                            onCodeBlockClick = onCodeBlockClick
                        )
                    }
                    if (hasBeenEdited) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (actualIsMine) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edited",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                messageBody()
                            } else {
                                messageBody()
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edited",
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    } else {
                        messageBody()
                    }
                }
            }
        }

        // For others' messages, show read receipts on the right of the bubble
        if (!actualIsMine && readReceipts.isNotEmpty()) {
            Spacer(modifier = Modifier.width(ReadReceiptGap))
            AnimatedInlineReadReceiptAvatars(
                receipts = readReceipts,
                userProfileCache = userProfileCache,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                appViewModel = appViewModel,
                messageSender = event.sender,
                eventId = event.eventId,
                roomId = event.roomId,
                onUserClick = onUserClick,
                isMine = false
            )
        }
    }

    // Add reaction badges for this message
    if (appViewModel != null) {
        val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
            appViewModel.messageReactions[event.eventId] ?: emptyList()
        }
        if (reactions.isNotEmpty()) {
            val textReactionInset = 12.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .reactionHorizontalInset(actualIsMine, textReactionInset),
                horizontalArrangement =
                    if (actualIsMine) Arrangement.End else Arrangement.Start
            ) {
                ReactionBadges(
                    eventId = event.eventId,
                    reactions = reactions,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    isMine = actualIsMine,
                    bubbleColor = bubbleColor,
                    onReactionClick = { emoji ->
                        appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                    }
                )
            }
        }
    }
}

/**
 * Finds the reply target event by checking:
 * 1. timelineEvents (currently displayed events)
 * 2. RoomTimelineCache (all cached events for the room) - checked reactively
 * 3. Fetching via get_event if not found
 * 
 * Returns null if the event cannot be found or fetched.
 */
@Composable
private fun rememberReplyTargetEvent(
    replyInfo: ReplyInfo?,
    timelineEvents: List<TimelineEvent>,
    roomId: String,
    appViewModel: AppViewModel?
): TimelineEvent? {
    if (replyInfo == null) return null
    
    // State to track fetched event
    var fetchedEvent by remember(replyInfo.eventId) { mutableStateOf<TimelineEvent?>(null) }
    var isFetching by remember(replyInfo.eventId) { mutableStateOf(false) }
    var fetchFailed by remember(replyInfo.eventId) { mutableStateOf(false) }
    
    // Track timeline update counter to reactively check cache when timeline updates
    val timelineUpdateCounter = appViewModel?.timelineUpdateCounter ?: 0
    
    // First, check timelineEvents (currently displayed events)
    val eventInTimeline = remember(replyInfo.eventId, timelineEvents) {
        timelineEvents.find { it.eventId == replyInfo.eventId }
    }
    if (eventInTimeline != null) {
        return eventInTimeline
    }
    
    // Second, check RoomTimelineCache (all cached events) - check reactively using timelineUpdateCounter
    // This ensures we re-check when related_events are added to the cache
    val eventInCache = remember(replyInfo.eventId, roomId, timelineUpdateCounter) {
        RoomTimelineCache.getCachedEvents(roomId)?.find { it.eventId == replyInfo.eventId }
    }
    if (eventInCache != null) {
        return eventInCache
    }
    
    // Third, if we have appViewModel, try fetching via get_event
    // Only fetch if we haven't already fetched, aren't currently fetching, and haven't failed
    if (appViewModel != null && fetchedEvent == null && !isFetching && !fetchFailed) {
        LaunchedEffect(replyInfo.eventId, roomId) {
            // Double-check inside LaunchedEffect to avoid race conditions
            if (fetchedEvent != null || isFetching || fetchFailed) {
                return@LaunchedEffect
            }
            
            isFetching = true
            if (BuildConfig.DEBUG) {
                Log.d("Andromuks", "TimelineEventItem: Reply target event ${replyInfo.eventId} not found in timeline or cache, fetching via get_event")
            }
            
            appViewModel.getEvent(roomId, replyInfo.eventId) { event ->
                isFetching = false
                if (event != null) {
                    if (BuildConfig.DEBUG) {
                        Log.d("Andromuks", "TimelineEventItem: Successfully fetched reply target event ${replyInfo.eventId}")
                    }
                    // Add the fetched event to the cache
                    val memberMap = RoomMemberCache.getRoomMembers(roomId)
                    val eventsJsonArray = org.json.JSONArray()
                    eventsJsonArray.put(event.toRawJsonObject())
                    RoomTimelineCache.addEventsFromSync(roomId, eventsJsonArray, memberMap)
                    fetchedEvent = event
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.w("Andromuks", "TimelineEventItem: Failed to fetch reply target event ${replyInfo.eventId}")
                    }
                    fetchFailed = true
                }
            }
        }
    }
    
    // Return fetched event if available, otherwise null (will show "Reply to unknown event")
    return fetchedEvent
}

@Composable
private fun rememberReplyPreviewColors(
    colorScheme: ColorScheme,
    replyInfo: ReplyInfo,
    originalEvent: TimelineEvent,
    myUserId: String?,
    appViewModel: AppViewModel?
): BubbleColors {
    val replySenderIsMine = replyInfo.sender == myUserId
    val replyMentionsMe = !replySenderIsMine && isMentioningUser(originalEvent, myUserId)
    val replyHasSpoiler = originalEvent.containsSpoilerContent()
    val replyIsThread = originalEvent.isThreadMessage()
    val replyIsRedacted = originalEvent.redactedBy != null
    val replyIsEdited = remember(replyInfo.eventId, appViewModel?.timelineUpdateCounter) {
        appViewModel?.isMessageEdited(replyInfo.eventId) ?: false
    }

    return remember(
        colorScheme,
        replyInfo.eventId,
        replySenderIsMine,
        replyMentionsMe,
        replyHasSpoiler,
        replyIsThread,
        replyIsRedacted,
        replyIsEdited
    ) {
        BubblePalette.colors(
            colorScheme = colorScheme,
            isMine = replySenderIsMine,
            isEdited = replyIsEdited,
            mentionsMe = replyMentionsMe,
            isThreadMessage = replyIsThread,
            hasSpoiler = replyHasSpoiler,
            isRedacted = replyIsRedacted
        )
    }
}

@Composable
private fun EncryptedMessageContent(
    event: TimelineEvent,
    timelineEvents: List<TimelineEvent>,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>,
    actualIsMine: Boolean,
    myUserId: String?,
    isConsecutive: Boolean,
    displayName: String?,
    avatarUrl: String?,
    mentionsMe: Boolean,
    readReceipts: List<ReadReceipt>,
    editedBy: TimelineEvent?,
    appViewModel: AppViewModel?,
    onScrollToMessage: (String) -> Unit,
    onReply: (TimelineEvent) -> Unit,
    onReact: (TimelineEvent) -> Unit,
    onEdit: (TimelineEvent) -> Unit,
    onDelete: (TimelineEvent) -> Unit,
    onUserClick: (String) -> Unit,
    onRoomLinkClick: (RoomLink) -> Unit,
    onThreadClick: (TimelineEvent) -> Unit,
    onShowEditHistory: (() -> Unit)? = null,
    onCodeBlockClick: (String) -> Unit = {},
    onShowMenu: ((MessageMenuConfig) -> Unit)? = null
) {
    // Check if this is an edit event (m.replace relationship) - don't display edit events
    val isEditEvent =
        event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") ==
            "m.replace"
    if (isEditEvent) {
        if (BuildConfig.DEBUG) android.util.Log.d(
            "Andromuks",
            "RoomTimelineScreen: Filtering out edit event ${event.eventId}"
        )
        return // Don't display edit events as separate timeline items
    }

    val decryptedType = event.decryptedType
    val decrypted = event.decrypted
    val encryptedHasBeenEdited = remember(event.eventId, appViewModel?.timelineUpdateCounter) {
        appViewModel?.isMessageEdited(event.eventId) ?: false
    }
    val colorScheme = MaterialTheme.colorScheme
    if (decryptedType == "m.room.message") {
        val format = decrypted?.optString("format", "")
        val body =
            if (format == "org.matrix.custom.html") {
                decrypted?.optString("formatted_body", "") ?: ""
            } else {
                decrypted?.optString("body", "") ?: ""
            }
        val msgType = decrypted?.optString("msgtype", "") ?: ""
        
        // Handle encrypted m.emote messages with narrator rendering
        if (msgType == "m.emote") {
            Column(modifier = Modifier.fillMaxWidth()) {
                EmoteEventNarrator(
                    event = event,
                    displayName = displayName ?: event.sender,
                    avatarUrl = avatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    onReply = { onReply(event) },
                    onReact = { onReact(event) },
                    onEdit = { onEdit(event) },
                    onDelete = { onDelete(event) },
                    onUserClick = onUserClick
                )
                
                // Add reaction badges for encrypted emote messages
                if (appViewModel != null) {
                    val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                        appViewModel.messageReactions[event.eventId] ?: emptyList()
                    }
                    if (reactions.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            ReactionBadges(
                                eventId = event.eventId,
                                reactions = reactions,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                isMine = actualIsMine,
                                onReactionClick = { emoji ->
                                    appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                                }
                            )
                        }
                    }
                }
            }
            return
        }

        // OPTIMIZED: Check if this message has been redacted using O(1) lookup
        val isRedacted = event.redactedBy != null
        if (BuildConfig.DEBUG && isRedacted) {
            Log.d("Andromuks", "EncryptedMessageContent: Event ${event.eventId} is redacted (redactedBy=${event.redactedBy}), msgType=$msgType")
        }
        val redactionEvent = if (isRedacted && appViewModel != null) {
            appViewModel.getRedactionEvent(event.eventId)  // O(1) lookup!
        } else null
        
        // Fallback: If cache lookup failed (e.g., for pagination events), search timeline events
        val finalRedactionEvent = redactionEvent ?: if (isRedacted) {
            @Suppress("DEPRECATION")
            net.vrkknn.andromuks.utils.RedactionUtils.findLatestRedactionEvent(event.eventId, timelineEvents)
        } else null
        
        val redactionSender = finalRedactionEvent?.sender

        // Request profile if redaction sender is missing from cache
        if (isRedacted && redactionSender != null && appViewModel != null) {
            if (!userProfileCache.containsKey(redactionSender)) {
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Requesting profile for encrypted message redaction sender: $redactionSender in room ${event.roomId}"
                )
                appViewModel.requestUserProfile(redactionSender, event.roomId)
            }
        }

        // Check if this is an edit (m.replace relationship)
        val isEdit =
            decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") ==
                "m.replace"
        val editContent =
            if (isEdit) {
                decrypted?.optJSONObject("m.new_content")
            } else null

        // Use edit content if this message is being edited, or show deletion message if redacted
        val finalBody =
            if (isRedacted) {
                // Create deletion message using cached redaction event (or fallback to timeline search)
                net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                    finalRedactionEvent,
                    userProfileCache
                )
            } else if (editedBy != null && editedBy.decrypted != null) {
                val newContent = editedBy.decrypted?.optJSONObject("m.new_content")
                val editFormat = newContent?.optString("format", "")
                if (editFormat == "org.matrix.custom.html") {
                    newContent?.optString("formatted_body", "") ?: ""
                } else {
                    newContent?.optString("body", "") ?: ""
                }
            } else if (editedBy != null && editedBy.content != null) {
                val newContent = editedBy.content?.optJSONObject("m.new_content")
                val editFormat = newContent?.optString("format", "")
                if (editFormat == "org.matrix.custom.html") {
                    newContent?.optString("formatted_body", "") ?: ""
                } else {
                    newContent?.optString("body", "") ?: ""
                }
            } else {
                body
            }

        val contentForHtml = editContent ?: decrypted
        val isHorizontalRule = !isRedacted && isHorizontalRuleMessage(event, contentForHtml, msgType)
        if (isHorizontalRule) {
            HorizontalRuleMessage()
            return
        }

        // Check if this is a reply message
        val replyInfo = event.getReplyInfo()
        val originalEvent =
            replyInfo?.let { reply ->
                timelineEvents.find<TimelineEvent> { it.eventId == reply.eventId }
            }

        // CRITICAL: Check if message is redacted BEFORE checking media type
        // Redacted messages should show deletion message, not original content (including media)
        // If redacted, render deletion message using MessageBubbleWithMenu
        if (isRedacted) {
            if (BuildConfig.DEBUG) {
                Log.d("Andromuks", "EncryptedMessageContent: Rendering redacted message ${event.eventId}, deletionMessage='$finalBody'")
            }
            val deletionMessage = finalBody // finalBody already contains the deletion message when isRedacted is true
            
            val bubbleShape =
                if (actualIsMine) {
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 2.dp,
                        bottomEnd = 12.dp,
                        bottomStart = 12.dp
                    )
                } else {
                    RoundedCornerShape(
                        topStart = 2.dp,
                        topEnd = 12.dp,
                        bottomEnd = 12.dp,
                        bottomStart = 12.dp
                    )
                }

            val deletionColors = BubblePalette.colors(
                colorScheme = MaterialTheme.colorScheme,
                isMine = actualIsMine,
                isRedacted = true
            )
            val bubbleColor = deletionColors.container
            val textColor = deletionColors.content

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    if (actualIsMine) Arrangement.End else Arrangement.Start
            ) {
                MessageBubbleWithMenu(
                    event = event,
                    bubbleColor = bubbleColor,
                    bubbleShape = bubbleShape,
                    modifier = Modifier
                        .padding(top = 4.dp),
                    isMine = actualIsMine,
                    myUserId = myUserId,
                    powerLevels = appViewModel?.currentRoomState?.powerLevels,
                    onReply = { onReply(event) },
                    onReact = { onReact(event) },
                    onEdit = { onEdit(event) },
                    onDelete = { onDelete(event) },
                    appViewModel = appViewModel,
                    onBubbleClick = { onThreadClick(event) },
                    onShowEditHistory = null,
                onShowMenu = onShowMenu,
                    mentionBorder = deletionColors.mentionBorder
                ) {
                    Text(
                        text = deletionMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            return
        }

        // Check if it's a media message (only for non-redacted messages)
        if (msgType == "m.image" || msgType == "m.video" || msgType == "m.audio" || msgType == "m.file") {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "TimelineEventItem: Found encrypted media message - msgType=$msgType, body=$body"
            )

            // Debug: Check what's in the decrypted object
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "TimelineEventItem: Direct url field: ${decrypted?.optString("url", "NOT_FOUND")}"
            )
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "TimelineEventItem: File object exists: ${decrypted?.has("file")}"
            )
            if (decrypted?.has("file") == true) {
                val fileObj = decrypted.optJSONObject("file")
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "TimelineEventItem: File url field: ${fileObj?.optString("url", "NOT_FOUND")}"
                )
            }

            // For encrypted messages, URL might be in file.url
            // Check if media is encrypted (has file object) or just the event is
            // encrypted (has url field)
            val fileObj = decrypted?.optJSONObject("file")
            val hasEncryptedFile = fileObj != null
            val directUrl = decrypted?.optString("url", "") ?: ""
            val fileUrl = fileObj?.optString("url", "") ?: ""
            val url = directUrl.takeIf { it.isNotBlank() } ?: fileUrl

            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "TimelineEventItem: URL extraction - directUrl='$directUrl', fileObj=${fileObj != null}, fileUrl='$fileUrl', finalUrl='$url', hasEncryptedFile=$hasEncryptedFile"
            )

            val filename = decrypted?.optString("filename", "") ?: ""
            val info = decrypted?.optJSONObject("info")

            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "TimelineEventItem: Encrypted media data - url=$url, filename=$filename, info=${info != null}"
            )

            if (url.isNotBlank() && info != null) {
                val width = info.optInt("w", 0)
                val height = info.optInt("h", 0)
                val size = info.optLong("size", 0)
                val mimeType = info.optString("mimetype", "")
                val blurHash =
                    info.optString("xyz.amorgan.blurhash")?.takeIf {
                        it.isNotBlank()
                    }

                // Extract thumbnail info for encrypted media (images and videos)
                // For encrypted media, thumbnail is in thumbnail_file.url, not thumbnail_url
                var thumbnailIsEncrypted = false
                val thumbnailUrl = if (msgType == "m.video" || msgType == "m.image") {
                    val thumbnailFile = info.optJSONObject("thumbnail_file")
                    if (thumbnailFile != null) {
                        // Encrypted thumbnail
                        thumbnailIsEncrypted = true
                        thumbnailFile.optString("url", "")?.takeIf { it.isNotBlank() }
                    } else {
                        // Unencrypted thumbnail (fallback)
                        info.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
                    }
                } else null
                
                // FIX: Read thumbnail_info for both images and videos, not just videos
                val thumbnailInfo = if (msgType == "m.video" || msgType == "m.image") {
                    info.optJSONObject("thumbnail_info")
                } else null
                
                val thumbnailBlurHash = thumbnailInfo?.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
                // CRITICAL FIX: Return nullable Int? and only populate when value exists and is > 0
                val thumbnailWidth = thumbnailInfo?.optInt("w", 0)?.takeIf { it > 0 }
                val thumbnailHeight = thumbnailInfo?.optInt("h", 0)?.takeIf { it > 0 }
                val duration = if (msgType == "m.video" || msgType == "m.audio") {
                    info.optInt("duration", 0).takeIf { it > 0 }
                } else null

                // Extract is_animated from MSC4230 (for animated images: GIF, animated PNG, animated WebP)
                val isAnimated = if (msgType == "m.image") {
                    info.optBoolean("is_animated", false).takeIf { info.has("is_animated") }
                } else null

                // Extract caption: use sanitized_html if available, otherwise body (only if different from filename)
                // Caption is only body if: 1) filename field exists, 2) body differs from filename, 3) body is not blank
                // If filename field is missing, body IS the filename, not a caption
                val caption = if (filename.isNotBlank() && body != filename && body.isNotBlank()) {
                    val localContent = event.localContent
                    val sanitizedHtml = localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
                    // Use sanitized_html if available and different from filename, otherwise use body
                    if (sanitizedHtml != null && sanitizedHtml != filename) {
                        sanitizedHtml
                    } else {
                        body
                    }
                } else null

                val mediaInfo =
                    MediaInfo(
                        width = width,
                        height = height,
                        size = size,
                        mimeType = mimeType,
                        blurHash = blurHash,
                        thumbnailUrl = thumbnailUrl,
                        thumbnailBlurHash = thumbnailBlurHash,
                        thumbnailWidth = thumbnailWidth,
                        thumbnailHeight = thumbnailHeight,
                        duration = duration,
                        thumbnailIsEncrypted = thumbnailIsEncrypted,
                        isAnimated = isAnimated
                    )

                val mediaMessage =
                    MediaMessage(
                        url = url,
                        filename = filename,
                        caption = caption,
                        info = mediaInfo,
                        msgType = msgType
                    )

                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "TimelineEventItem: Created encrypted MediaMessage - url=${mediaMessage.url}, blurHash=${mediaMessage.info.blurHash}"
                )

                val encryptedMediaIsThreadMessage = event.isThreadMessage()
                val encryptedMediaHasBeenEdited =
                    remember(event.eventId, appViewModel?.timelineUpdateCounter) {
                        appViewModel?.isMessageEdited(event.eventId) ?: false
                    }
                val encryptedMediaBubbleColor =
                    remember(encryptedMediaHasBeenEdited, encryptedMediaIsThreadMessage, actualIsMine, colorScheme) {
                        mediaBubbleColorFor(
                            colorScheme = colorScheme,
                            isMine = actualIsMine,
                            isThreadMessage = encryptedMediaIsThreadMessage,
                            hasBeenEdited = encryptedMediaHasBeenEdited
                        )
                    }

                // Display encrypted media message with nested reply structure
                MediaMessageItem(
                    mediaMessage = mediaMessage,
                    replyInfo = replyInfo,
                    originalEvent = originalEvent,
                    userProfileCache = userProfileCache,
                    homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                    authToken = authToken,
                    isMine = actualIsMine,
                    hasEncryptedFile = hasEncryptedFile,
                    event = event,
                    isConsecutive = isConsecutive,
                    editedBy = editedBy,
                    timelineEvents = timelineEvents,
                    onScrollToMessage = onScrollToMessage,
                    onReply = { onReply(event) },
                    onReact = { onReact(event) },
                    onEdit = { onEdit(event) },
                    onDelete = { onDelete(event) },
                    onUserClick = onUserClick,
                    appViewModel = appViewModel,
                    myUserId = myUserId,
                    powerLevels = appViewModel?.currentRoomState?.powerLevels,
                    onBubbleClick = if (encryptedMediaIsThreadMessage) {
                        { onThreadClick(event) }
                    } else {
                        null
                    },
                    onShowEditHistory = onShowEditHistory,
                    precomputedHasBeenEdited = encryptedMediaHasBeenEdited
                )

                // Add reaction badges for encrypted media messages
                if (appViewModel != null) {
                    val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                        appViewModel.messageReactions[event.eventId] ?: emptyList()
                    }
                    if (reactions.isNotEmpty()) {
                        val mediaReactionInset = 12.dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .reactionHorizontalInset(actualIsMine, mediaReactionInset),
                            horizontalArrangement =
                                if (actualIsMine) Arrangement.End
                                else Arrangement.Start
                        ) {
                            ReactionBadges(
                                eventId = event.eventId,
                                reactions = reactions,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                isMine = actualIsMine,
                                bubbleColor = encryptedMediaBubbleColor,
                                onReactionClick = { emoji ->
                                    appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                                }
                            )
                        }
                    }
                }
            } else {
                // Fallback to text message if encrypted media parsing fails
                val bubbleShape =
                    if (actualIsMine) {
                        RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 2.dp,
                            bottomEnd = 8.dp,
                            bottomStart = 12.dp
                        )
                    } else {
                        RoundedCornerShape(
                            topStart = 2.dp,
                            topEnd = 12.dp,
                            bottomEnd = 12.dp,
                            bottomStart = 8.dp
                        )
                    }
                
                val fallbackColors = BubblePalette.colors(
                    colorScheme = colorScheme,
                    isMine = actualIsMine,
                    isEdited = encryptedHasBeenEdited,
                    mentionsMe = mentionsMe
                )
                val bubbleColor = fallbackColors.container
                val textColor = fallbackColors.content

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        if (actualIsMine) Arrangement.End else Arrangement.Start
                ) {
                    MessageBubbleWithMenu(
                        event = event,
                        bubbleColor = bubbleColor,
                        bubbleShape = bubbleShape,
                        modifier = Modifier.widthIn(max = 300.dp),
                        isMine = actualIsMine,
                        myUserId = myUserId,
                        powerLevels = appViewModel?.currentRoomState?.powerLevels,
                        onReply = { onReply(event) },
                        onReact = { onReact(event) },
                        onEdit = { onEdit(event) },
                        onDelete = { onDelete(event) },
                        appViewModel = appViewModel,
                        onBubbleClick = null,
                        mentionBorder = fallbackColors.mentionBorder,
                        onShowMenu = onShowMenu
                    ) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier =
                                Modifier.padding(
                                    horizontal = 8.dp,
                                    vertical = 6.dp
                                )
                        )
                    }
                }

                // Add reaction badges for encrypted text message
                if (appViewModel != null) {
                    val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                        appViewModel.messageReactions[event.eventId] ?: emptyList()
                    }
                    if (reactions.isNotEmpty()) {
                        val fallbackReactionInset = 8.dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .reactionHorizontalInset(actualIsMine, fallbackReactionInset),
                            horizontalArrangement =
                                if (actualIsMine) Arrangement.End
                                else Arrangement.Start
                        ) {
                            ReactionBadges(
                                eventId = event.eventId,
                                reactions = reactions,
                                homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            isMine = actualIsMine,
                            bubbleColor = bubbleColor,
                                onReactionClick = { emoji ->
                                    appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Regular encrypted text message
            val bubbleShape =
                if (actualIsMine) {
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 2.dp,
                        bottomEnd = 8.dp,
                        bottomStart = 12.dp
                    )
                } else {
                    RoundedCornerShape(
                        topStart = 2.dp,
                        topEnd = 12.dp,
                        bottomEnd = 12.dp,
                        bottomStart = 8.dp
                    )
                }
            
            val hasBeenEdited = encryptedHasBeenEdited
            
            // Check if this is a thread message
            val isThreadMessage = event.isThreadMessage()
            
            
            val containsSpoiler = event.containsSpoilerContent()
            val bubbleColors = remember(
                event.eventId,
                actualIsMine,
                hasBeenEdited,
                mentionsMe,
                isThreadMessage,
                containsSpoiler
            ) {
                BubblePalette.colors(
                    colorScheme = colorScheme,
                    isMine = actualIsMine,
                    isEdited = hasBeenEdited,
                    mentionsMe = mentionsMe,
                    isThreadMessage = isThreadMessage,
                    hasSpoiler = containsSpoiler
                )
            }
            val bubbleColor = bubbleColors.container
            val textColor = bubbleColors.content

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    if (actualIsMine) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                // For my messages, show read receipts on the left of the bubble
                if (actualIsMine && readReceipts.isNotEmpty()) {
                    AnimatedInlineReadReceiptAvatars(
                        receipts = readReceipts,
                        userProfileCache = userProfileCache,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        appViewModel = appViewModel,
                        messageSender = event.sender,
                        eventId = event.eventId,
                        roomId = event.roomId,
                        onUserClick = onUserClick,
                        isMine = true
                    )
                    Spacer(modifier = Modifier.width(ReadReceiptGap))
                }

                // Display encrypted text message with nested reply structure (but NOT for thread messages)
                // Thread messages are rendered as normal bubbles with different color
                if (replyInfo != null && originalEvent != null && !isThreadMessage) {
                    MessageBubbleWithMenu(
                        event = event,
                        bubbleColor = bubbleColor,
                        bubbleShape = bubbleShape,
                        modifier = Modifier.widthIn(max = 300.dp),
                        isMine = actualIsMine,
                        myUserId = myUserId,
                        powerLevels = appViewModel?.currentRoomState?.powerLevels,
                        onReply = { onReply(event) },
                        onReact = { onReact(event) },
                        onEdit = { onEdit(event) },
                        onDelete = { onDelete(event) },
                        appViewModel = appViewModel,
                        onBubbleClick = if (isThreadMessage) {
                            { onThreadClick(event) }
                        } else {
                            null
                        },
                        onShowEditHistory = if (hasBeenEdited) onShowEditHistory else null,
                        mentionBorder = bubbleColors.mentionBorder,
                        onShowMenu = onShowMenu
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            val replyPreviewColors = rememberReplyPreviewColors(
                                colorScheme = colorScheme,
                                replyInfo = replyInfo,
                                originalEvent = originalEvent,
                                myUserId = myUserId,
                                appViewModel = appViewModel
                            )
                            // Reply preview (clickable original message)
                            ReplyPreview(
                                replyInfo = replyInfo,
                                originalEvent = originalEvent,
                                userProfileCache = userProfileCache,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                previewColors = replyPreviewColors,
                                modifier =
                                    Modifier.padding(bottom = 6.dp)
                                        .align(Alignment.Start),
                                onOriginalMessageClick = {
                                    onScrollToMessage(replyInfo.eventId)
                                },
                                timelineEvents = timelineEvents,
                                onMatrixUserClick = onUserClick,
                                appViewModel = appViewModel
                            )

                            // Reply message content with inline timestamp
                            // Render text bubble with optional edit icon (encrypted)
                            val messageBody: @Composable () -> Unit = {
                                AdaptiveMessageText(
                                    event = event,
                                    body = body,
                                    format = format,
                                    userProfileCache = userProfileCache,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    appViewModel = appViewModel,
                                    roomId = event.roomId,
                                    textColor = textColor,
                                    onUserClick = onUserClick,
                                    onMatrixUserClick = onUserClick,
                                    onRoomLinkClick = onRoomLinkClick,
                                    onCodeBlockClick = onCodeBlockClick
                                )
                            }
                            if (encryptedHasBeenEdited) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (actualIsMine) {
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = "Edited",
                                            tint = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        messageBody()
                                    } else {
                                        messageBody()
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = "Edited",
                                            tint = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            } else {
                                messageBody()
                            }
                        }
                    }
                } else {
                    // Regular encrypted message bubble
                    MessageBubbleWithMenu(
                        event = event,
                        bubbleColor = bubbleColor,
                        bubbleShape = bubbleShape,
                        modifier = Modifier.widthIn(max = 300.dp),
                        isMine = actualIsMine,
                        myUserId = myUserId,
                        powerLevels = appViewModel?.currentRoomState?.powerLevels,
                        onReply = { onReply(event) },
                        onReact = { onReact(event) },
                        onEdit = { onEdit(event) },
                        onDelete = { onDelete(event) },
                        appViewModel = appViewModel,
                        onBubbleClick = if (isThreadMessage) {
                            { onThreadClick(event) }
                        } else {
                            null
                        },
                        onShowEditHistory = if (hasBeenEdited) onShowEditHistory else null,
                        mentionBorder = bubbleColors.mentionBorder,
                        onShowMenu = onShowMenu
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            // finalBody already contains deletion message if redacted
                            // Render text bubble with optional edit icon (encrypted)
                            val messageBody: @Composable () -> Unit = {
                                AdaptiveMessageText(
                                    event = event,
                                    body = body,
                                    format = format,
                                    userProfileCache = userProfileCache,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    appViewModel = appViewModel,
                                    roomId = event.roomId,
                                    textColor = textColor,
                                    onUserClick = onUserClick,
                                    onMatrixUserClick = onUserClick,
                                    onRoomLinkClick = onRoomLinkClick,
                                    onCodeBlockClick = onCodeBlockClick
                                )
                            }
                            if (encryptedHasBeenEdited) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (actualIsMine) {
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = "Edited",
                                            tint = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        messageBody()
                                    } else {
                                        messageBody()
                                        Icon(
                                            imageVector = Icons.Outlined.Edit,
                                            contentDescription = "Edited",
                                            tint = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            } else {
                                messageBody()
                            }
                        }
                    }
                }

                    // For others' messages, show read receipts on the right of the bubble
                    if (!actualIsMine && readReceipts.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(ReadReceiptGap))
                        AnimatedInlineReadReceiptAvatars(
                            receipts = readReceipts,
                            userProfileCache = userProfileCache,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            appViewModel = appViewModel,
                            messageSender = event.sender,
                            eventId = event.eventId,
                            roomId = event.roomId,
                            onUserClick = onUserClick,
                            isMine = false
                        )
                }
            }

            // Add reaction badges for encrypted text message
            if (appViewModel != null) {
                val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                    appViewModel.messageReactions[event.eventId] ?: emptyList()
                }
                if (reactions.isNotEmpty()) {
                    val encryptedReactionInset = 8.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .reactionHorizontalInset(actualIsMine, encryptedReactionInset),
                        horizontalArrangement =
                            if (actualIsMine) Arrangement.End else Arrangement.Start
                    ) {
                        ReactionBadges(
                            eventId = event.eventId,
                            reactions = reactions,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            isMine = actualIsMine,
                            bubbleColor = bubbleColor,
                            onReactionClick = { emoji ->
                                appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                            }
                        )
                    }
                }
            }
        }
    } else if (decryptedType == "m.sticker") {
        // Handle encrypted stickers
        val stickerMessage = extractStickerFromEvent(event)
        if (stickerMessage != null) {
            StickerMessageContent(
                event = event,
                actualIsMine = actualIsMine,
                readReceipts = readReceipts,
                userProfileCache = userProfileCache,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                appViewModel = appViewModel,
                onUserClick = onUserClick,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                myUserId = myUserId,
                isConsecutive = isConsecutive,
                onThreadClick = onThreadClick
            )
        } else {
            Log.w(
                "Andromuks",
                "TimelineEventItem: Failed to extract encrypted sticker data from event ${event.eventId}"
            )
        }
    } else {
        Text(
            text = "Encrypted message",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StickerMessageContent(
    event: TimelineEvent,
    actualIsMine: Boolean,
    readReceipts: List<ReadReceipt>,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    appViewModel: AppViewModel?,
    onUserClick: (String) -> Unit,
    onReply: (TimelineEvent) -> Unit,
    onReact: (TimelineEvent) -> Unit,
    onEdit: (TimelineEvent) -> Unit,
    onDelete: (TimelineEvent) -> Unit,
    myUserId: String?,
    isConsecutive: Boolean,
    onThreadClick: (TimelineEvent) -> Unit,
    onShowMenu: ((MessageMenuConfig) -> Unit)? = null
) {
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "StickerMessageContent: Rendering sticker event ${event.eventId}, isConsecutive=$isConsecutive"
    )

    // Show deletion bubble if redacted
    if (event.redactedBy != null) {
        val redactionEvent = appViewModel?.getRedactionEvent(event.eventId)
        
        // Fallback: If cache lookup failed (e.g., for pagination events), search cached timeline events
        val finalRedactionEvent = redactionEvent ?: run {
            val cachedEvents = net.vrkknn.andromuks.RoomTimelineCache.getCachedEvents(event.roomId)
            if (cachedEvents != null) {
                @Suppress("DEPRECATION")
                net.vrkknn.andromuks.utils.RedactionUtils.findLatestRedactionEvent(event.eventId, cachedEvents)
            } else {
                null
            }
        }
        
        val deletionMessage =
            net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                finalRedactionEvent,
                userProfileCache
            )

        val bubbleShape =
            if (actualIsMine) {
                RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 2.dp,
                    bottomEnd = 12.dp,
                    bottomStart = 12.dp
                )
            } else {
                RoundedCornerShape(
                    topStart = 2.dp,
                    topEnd = 12.dp,
                    bottomEnd = 12.dp,
                    bottomStart = 12.dp
                )
            }

        val deletionColors = BubblePalette.colors(
            colorScheme = MaterialTheme.colorScheme,
            isMine = actualIsMine,
            isRedacted = true
        )
        val bubbleColor = deletionColors.container
        val textColor = deletionColors.content
        val isDarkMode = isSystemInDarkTheme()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                if (actualIsMine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (actualIsMine && readReceipts.isNotEmpty()) {
                AnimatedInlineReadReceiptAvatars(
                    receipts = readReceipts,
                    userProfileCache = userProfileCache,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    appViewModel = appViewModel,
                    messageSender = event.sender,
                    eventId = event.eventId,
                    roomId = event.roomId,
                    onUserClick = onUserClick,
                    isMine = true
                )
                Spacer(modifier = Modifier.width(ReadReceiptGap))
            }

            MessageBubbleWithMenu(
                event = event,
                bubbleColor = bubbleColor,
                bubbleShape = bubbleShape,
                modifier = Modifier
                    .padding(top = 4.dp),
                isMine = actualIsMine,
                myUserId = myUserId,
                powerLevels = appViewModel?.currentRoomState?.powerLevels,
                onReply = { onReply(event) },
                onReact = { onReact(event) },
                onEdit = { onEdit(event) },
                onDelete = { onDelete(event) },
                appViewModel = appViewModel,
                onBubbleClick = { onThreadClick(event) },
                onShowEditHistory = null,
                onShowMenu = onShowMenu,
                mentionBorder = deletionColors.mentionBorder
            ) {
                Text(
                    text = deletionMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        return
    }

    val stickerMessage = extractStickerFromEvent(event)
    val stickerIsThreadMessage = event.isThreadMessage()
    val colorScheme = MaterialTheme.colorScheme
    val stickerBubbleColor = stickerBubbleColorFor(
        colorScheme = colorScheme,
        isMine = actualIsMine,
        isThreadMessage = stickerIsThreadMessage
    )

    if (stickerMessage != null) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "TimelineEventItem: Found sticker - url=${stickerMessage.url}, body=${stickerMessage.body}, dimensions=${stickerMessage.width}x${stickerMessage.height}, isConsecutive=$isConsecutive"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                if (actualIsMine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            // For other users' messages, show read receipts on the right
            if (!actualIsMine && readReceipts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(ReadReceiptGap))
                AnimatedInlineReadReceiptAvatars(
                    receipts = readReceipts,
                    userProfileCache = userProfileCache,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    appViewModel = appViewModel,
                    messageSender = event.sender,
                    eventId = event.eventId,
                    roomId = event.roomId,
                    onUserClick = onUserClick,
                    isMine = false
                )
            }

            StickerMessage(
                stickerMessage = stickerMessage,
                homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                authToken = authToken,
                isMine = actualIsMine,
                isEncrypted = stickerMessage.hasEncryptedFile,
                event = event,
                timestamp = event.timestamp,
                isConsecutive = isConsecutive,
                onReply = { onReply(event) },
                onReact = { onReact(event) },
                onEdit = { onEdit(event) },
                onDelete = { onDelete(event) },
                myUserId = myUserId,
                powerLevels = appViewModel?.currentRoomState?.powerLevels,
                appViewModel = appViewModel,
                onBubbleClick = if (stickerIsThreadMessage) {
                    { onThreadClick(event) }
                } else {
                    null
                }
            )

            // For other users' messages, show read receipts on the right
            if (!actualIsMine && readReceipts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(ReadReceiptGap))
                AnimatedInlineReadReceiptAvatars(
                    receipts = readReceipts,
                    userProfileCache = userProfileCache,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    appViewModel = appViewModel,
                    messageSender = event.sender,
                    eventId = event.eventId,
                    roomId = event.roomId,
                    onUserClick = onUserClick,
                    isMine = false
                )
            }
        }

        // Add reaction badges for stickers
        if (appViewModel != null) {
            val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                appViewModel.messageReactions[event.eventId] ?: emptyList()
            }
            if (reactions.isNotEmpty()) {
                val stickerReactionInset = 12.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .reactionHorizontalInset(actualIsMine, stickerReactionInset),
                    horizontalArrangement =
                        if (actualIsMine) Arrangement.End else Arrangement.Start
                ) {
                    ReactionBadges(
                        eventId = event.eventId,
                        reactions = reactions,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isMine = actualIsMine,
                        bubbleColor = stickerBubbleColor,
                        onReactionClick = { emoji ->
                            appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                        }
                    )
                }
            }
        }
    } else {
        Log.w(
            "Andromuks",
            "TimelineEventItem: Failed to extract sticker data from event ${event.eventId}"
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TimelineEventItem(
    event: TimelineEvent,
    timelineEvents: List<TimelineEvent>,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, MemberProfile>,
    isMine: Boolean,
    myUserId: String?,
    isConsecutive: Boolean = false,
    appViewModel: AppViewModel? = null,
    sharedTransitionScope: SharedTransitionScope? = null,  // ← ADD THIS
    animatedVisibilityScope: AnimatedVisibilityScope? = null,  // ← ADD THIS
    onScrollToMessage: (String) -> Unit = {},
    onReply: (TimelineEvent) -> Unit = {},
    onReact: (TimelineEvent) -> Unit = {},
    onEdit: (TimelineEvent) -> Unit = {},
    onDelete: (TimelineEvent) -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onRoomLinkClick: (RoomLink) -> Unit = {},
    onThreadClick: (TimelineEvent) -> Unit = {},
    onNewBubbleAnimationStart: (() -> Unit)? = null,
    onCodeBlockClick: (String) -> Unit = {},
    onShowMenu: ((MessageMenuConfig) -> Unit)? = null
) {
    val context = LocalContext.current
    
    // OPPORTUNISTIC PROFILE LOADING: Request profile only when this event is actually rendered
    //LaunchedEffect(event.sender, event.roomId) {
    //    if (appViewModel != null) {
    //        // Check if we already have the profile (including for current user)
    //        val existingProfile = appViewModel.getUserProfile(event.sender, event.roomId)
    //        if (existingProfile == null || existingProfile.displayName.isNullOrBlank()) {
    //            if (BuildConfig.DEBUG) Log.d("Andromuks", "TimelineEventItem: Requesting profile on-demand for ${event.sender}")
    //            appViewModel.requestUserProfileOnDemand(event.sender, event.roomId)
    //        }
    //    }
    //}

    // Check for per-message profile (e.g., from Beeper bridge)
    // This can be in either the content (for regular messages) or decrypted content (for encrypted
    // messages)
    val perMessageProfile = event.content?.optJSONObject("com.beeper.per_message_profile")
    val encryptedPerMessageProfile =
        event.decrypted?.optJSONObject("com.beeper.per_message_profile")
    val hasPerMessageProfile = perMessageProfile != null
    val hasEncryptedPerMessageProfile = encryptedPerMessageProfile != null

    // Use per-message profile if available (prioritize encrypted over regular), otherwise fall back
    // to regular profile cache
    val actualProfile =
        when {
            hasEncryptedPerMessageProfile -> {
                val encryptedPerMessageDisplayName =
                    encryptedPerMessageProfile?.optString("displayname")?.takeIf { it.isNotBlank() }
                val encryptedPerMessageAvatarUrl =
                    encryptedPerMessageProfile?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                val encryptedPerMessageUserId =
                    encryptedPerMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }

                // Create a temporary profile object for encrypted per-message profile
                MemberProfile(encryptedPerMessageDisplayName, encryptedPerMessageAvatarUrl)
            }
            hasPerMessageProfile -> {
                val perMessageDisplayName =
                    perMessageProfile?.optString("displayname")?.takeIf { it.isNotBlank() }
                val perMessageAvatarUrl =
                    perMessageProfile?.optString("avatar_url")?.takeIf { it.isNotBlank() }
                val perMessageUserId =
                    perMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }

                // Create a temporary profile object for per-message profile
                MemberProfile(perMessageDisplayName, perMessageAvatarUrl)
            }
            else -> userProfileCache[event.sender]
        }

    val displayName = actualProfile?.displayName
    val avatarUrl = actualProfile?.avatarUrl

    // For per-message profiles, we also need to track the bridge sender
    val bridgeSender =
        if (hasPerMessageProfile || hasEncryptedPerMessageProfile) event.sender else null

    // For per-message profiles, check if the message is "mine" based on the per-message profile
    // user ID
    val actualIsMine =
        if (hasPerMessageProfile || hasEncryptedPerMessageProfile) {
            val perMessageUserId =
                if (hasEncryptedPerMessageProfile) {
                    encryptedPerMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }
                } else {
                    perMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }
                }
            myUserId != null && perMessageUserId == myUserId
        } else {
            isMine
        }

    // Check if the current user is mentioned in this message (calculated once for reuse)
    // IMPORTANT: Exclude automatic mentions from replies - when someone replies to your message,
    // Matrix automatically adds you to m.mentions, but that's not a "real" mention that should show a border
    // Only show mention border if the message actually mentions the user AND it's not just a reply to that user
    val replyInfoForMention = event.getReplyInfo()
    val originalEventForMention = replyInfoForMention?.let { reply ->
        timelineEvents.find<TimelineEvent> { it.eventId == reply.eventId }
    }
    val isReplyToMe = originalEventForMention?.sender == myUserId
    val mentionsMe = !actualIsMine && isMentioningUser(event, myUserId) && !isReplyToMe

    // Check if this is a narrator event (system event)
    val isNarratorEvent =
        event.type in
            setOf(
                "m.room.member",
                "m.room.name",
                "m.room.topic",
                "m.room.avatar",
                "m.room.pinned_events",
                "m.room.server_acl",
                "m.room.power_levels",
                "m.room.tombstone",
                "m.space.parent"
            )

    // Check if this message is being edited by another event (moved to function start)
    // Try getMessageVersions first (most reliable), then fallback to searching timelineEvents
    // This ensures we get the latest edit even if edit events are filtered from display
    val editedBy = remember(event.eventId, appViewModel?.timelineUpdateCounter, timelineEvents) {
        // First try getMessageVersions (uses cached version data)
        val versioned = appViewModel?.getMessageVersions(event.eventId)
        // Find the first version that is NOT the original (i.e., an edit)
        // Versions are sorted by timestamp (newest first), so the first non-original is the latest edit
        val editFromVersions = versioned?.versions?.firstOrNull { !it.isOriginal }?.event
        
        if (editFromVersions != null) {
            editFromVersions
        } else {
            // Fallback: search timelineEvents directly (unfiltered list should contain edit events)
            // This works even if getMessageVersions isn't populated yet
            val editsFromTimeline = timelineEvents.filter {
                (it.content?.optJSONObject("m.relates_to")?.optString("event_id") == event.eventId &&
                    it.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace") ||
                    (it.decrypted?.optJSONObject("m.relates_to")?.optString("event_id") ==
                        event.eventId &&
                        it.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") ==
                            "m.replace")
            }
            val latestEdit = editsFromTimeline.maxByOrNull { it.timestamp }
            latestEdit
        }
    }

    var showEditHistoryDialog by remember(event.eventId) { mutableStateOf(false) }
    var editHistoryLoading by remember(event.eventId) { mutableStateOf(false) }
    var editHistoryVersion by remember(event.eventId) { mutableStateOf<VersionedMessage?>(null) }
    var editHistoryError by remember(event.eventId) { mutableStateOf<String?>(null) }

    val openEditHistory = remember(appViewModel, event.eventId) {
        {
            if (appViewModel != null) {
                editHistoryError = null
                if (!showEditHistoryDialog) {
                    showEditHistoryDialog = true
                }
                if (editHistoryVersion == null) {
                    editHistoryLoading = true
                }
            }
        }
    }

    LaunchedEffect(showEditHistoryDialog, appViewModel?.timelineUpdateCounter) {
        if (showEditHistoryDialog && appViewModel != null) {
            editHistoryLoading = true
            try {
                val versioned = appViewModel.getMessageVersions(event.eventId)
                editHistoryVersion = versioned
                if (versioned == null) {
                    editHistoryError = "Edit history is not available"
                } else {
                    editHistoryError = null
                }
            } catch (t: Throwable) {
                android.util.Log.e("Andromuks", "TimelineEventItem: Failed to load edit history", t)
                editHistoryError = t.message ?: "Failed to load edit history"
            } finally {
                editHistoryLoading = false
            }
        }
        }

    if (isNarratorEvent) {
        // For narrator events, show only the small narrator content
        SystemEventNarrator(
            event = event,
            displayName = displayName ?: event.sender,
            avatarUrl = avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            appViewModel = appViewModel,
            roomId = event.roomId,
            myUserId = myUserId,
            powerLevels = appViewModel?.currentRoomState?.powerLevels,
            onUserClick = onUserClick,
            onRoomClick = { roomId ->
                // Convert room ID to RoomLink and call onRoomLinkClick
                onRoomLinkClick(RoomLink(roomIdOrAlias = roomId))
            },
            onReply = { event -> onReply(event) },
            onDelete = { event -> onDelete(event) },
            onShowMenu = onShowMenu
        )
        return
    }

    // Early return for edit events (m.replace relationships) - they should not be displayed as
    // separate timeline items
    val isEditEvent =
        (event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace") ||
            (event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace")
    if (isEditEvent) {
        return
    }

    // Calculate read receipts and recalculate when receipts are updated
    // OPTIMIZED: Use separate readReceiptsUpdateCounter to avoid unnecessary recomposition of timeline
    // CRITICAL FIX: Filter receipts to ensure they belong to this event's room
    // EventIds are globally unique, but we verify the receipt's eventId matches to prevent cross-room leakage
    val readReceipts =
        remember(event.eventId, event.roomId, appViewModel?.readReceiptsUpdateCounter) {
            if (appViewModel != null) {
                val allReceipts = net.vrkknn.andromuks.utils.ReceiptFunctions.getReadReceipts(
                    event.eventId,
                    appViewModel.getReadReceiptsMap()
                )
                // CRITICAL FIX: Filter receipts by both eventId AND roomId
                // This ensures receipts from other rooms (with same eventId) don't show up
                // Also handles backward compatibility: receipts without roomId (empty string) are shown for the current room
                allReceipts.filter { receipt ->
                    receipt.eventId == event.eventId && 
                    (receipt.roomId == event.roomId || receipt.roomId.isBlank()) // Match roomId or allow empty (backward compat)
                }
            } else {
                emptyList()
            }
        }

    // Early check for emote message (before rendering layout)
    val isEmoteMessage = when {
        event.type == "m.room.message" -> {
            val isEdit = event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            val content = if (isEdit) event.content?.optJSONObject("m.new_content") else event.content
            content?.optString("msgtype", "") == "m.emote"
        }
        event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> {
            event.decrypted?.optString("msgtype", "") == "m.emote"
        }
        else -> false
    }
    
    // PERFORMANCE: Removed all animations for stable performance base
    // Trigger sound notification only for messages we send (not received messages)
    val newMessageAnimations = appViewModel?.getNewMessageAnimations() ?: emptyMap()
    val isNewMessage = newMessageAnimations.containsKey(event.eventId)
    
    // Trigger sound notification once when our own message first appears
    // Only play sound for messages we send, not for received messages
    LaunchedEffect(event.eventId) {
        if (isNewMessage && isMine && onNewBubbleAnimationStart != null) {
            onNewBubbleAnimationStart.invoke()
        }
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Show avatar only for non-consecutive messages (and not for emotes, they have their own)
        if (!actualIsMine && !isConsecutive && !isEmoteMessage) {
            // For first messages, show avatar with timestamp below it
            Column(
                modifier = Modifier.width(AvatarColumnWidth), // Avatar width (wider to fit timestamp)
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.clickable { onUserClick(event.sender) }) {
                    // Don't use shared element for timeline message avatars
                    AvatarImage(
                        mxcUrl = avatarUrl,
                        homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                        authToken = authToken,
                        fallbackText = (displayName ?: event.sender).take(1),
                        size = 24.dp,
                        userId = event.sender,
                        displayName = displayName,
                        isVisible = true
                    )
                }
                // Timestamp below avatar (smaller font)
                val timestampText = if (editedBy != null) {
                    "${formatTimestamp(event.timestamp)} (edited at ${formatTimestamp(editedBy.timestamp)})"
                } else {
                    formatTimestamp(event.timestamp)
                }
                val timestampModifier = if (editedBy != null && appViewModel != null) {
                    Modifier.clickable { openEditHistory() }
                } else {
                    Modifier
                }
                Text(
                    text = timestampText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = timestampModifier
                )
            }
            Spacer(modifier = Modifier.width(AvatarGap))
        } else if (!actualIsMine && isConsecutive && !isEmoteMessage) {
            // Keep the same horizontal footprint as the avatar+gap used for first messages
            val timestampText = if (editedBy != null) {
                "${formatTimestamp(event.timestamp)} (edited at ${formatTimestamp(editedBy.timestamp)})"
            } else {
                formatTimestamp(event.timestamp)
            }
            val timestampModifier = if (editedBy != null && appViewModel != null) {
                Modifier.clickable { openEditHistory() }
            } else {
                Modifier
            }
            Box(
                modifier = Modifier.width(AvatarColumnWidth),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = timestampText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = timestampModifier
                )
            }
            Spacer(modifier = Modifier.width(AvatarGap))
        }

        // Event content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Show name and timestamp header only for non-consecutive messages (and not for emotes)
            if (!isConsecutive && !isEmoteMessage) {
                // For our messages, show display name right-aligned; for others, left-aligned
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (actualIsMine) Arrangement.End else Arrangement.Start
                ) {
                    // Show per-message profile name and bridge sender info
                    val (headerText, headerAnnotatedString) = if (
                        (hasPerMessageProfile || hasEncryptedPerMessageProfile) &&
                            bridgeSender != null
                    ) {
                        // Get bridge sender display name for better readability
                        val bridgeProfile = userProfileCache[bridgeSender]
                        val bridgeDisplayName = bridgeProfile?.displayName ?: bridgeSender
                        val fakeDisplayName = displayName ?: "Unknown"
                        
                        // Get the fake sender's user ID from the per-message profile
                        val fakeSenderId = when {
                            hasEncryptedPerMessageProfile -> {
                                encryptedPerMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }
                            }
                            hasPerMessageProfile -> {
                                perMessageProfile?.optString("id")?.takeIf { it.isNotBlank() }
                            }
                            else -> null
                        } ?: fakeDisplayName // Fallback to display name if no ID
                        
                        val plainText = "$fakeDisplayName, sent by $bridgeDisplayName"
                        
                        // Create annotated string with different colors for each part
                        val annotatedString = buildAnnotatedString {
                            // Fake display name (bridge name) - use fake sender's color
                            withStyle(
                                style = SpanStyle(
                                    color = net.vrkknn.andromuks.utils.UserColorUtils.getUserColor(fakeSenderId)
                                )
                            ) {
                                append(fakeDisplayName)
                            }
                            
                            // ", sent by " text - use Material3 colors
                            withStyle(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                append(", sent by ")
                            }
                            
                            // Real display name (actual sender) - use real sender's color
                            withStyle(
                                style = SpanStyle(
                                    color = net.vrkknn.andromuks.utils.UserColorUtils.getUserColor(bridgeSender)
                                )
                            ) {
                                append(bridgeDisplayName)
                            }
                        }
                        
                        Pair(plainText, annotatedString)
                    } else {
                        val plainText = displayName ?: event.sender
                        Pair(plainText, null)
                    }

                    if (headerAnnotatedString != null) {
                        Text(
                            text = headerAnnotatedString,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable { onUserClick(event.sender) }
                        )
                    } else {
                        Text(
                            text = headerText,
                            style = MaterialTheme.typography.labelMedium,
                            color = net.vrkknn.andromuks.utils.UserColorUtils.getUserColor(event.sender),
                            modifier = Modifier.clickable { onUserClick(event.sender) }
                        )
                    }
                }
            }

            MessageTypeContent(
                event = event,
                timelineEvents = timelineEvents,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                userProfileCache = userProfileCache,
                actualIsMine = actualIsMine,
                myUserId = myUserId,
                isConsecutive = isConsecutive,
                displayName = displayName,
                avatarUrl = avatarUrl,
                mentionsMe = mentionsMe,
                readReceipts = readReceipts,
                editedBy = editedBy,
                appViewModel = appViewModel,
                onScrollToMessage = onScrollToMessage,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                onUserClick = onUserClick,
                onRoomLinkClick = onRoomLinkClick,
                onThreadClick = onThreadClick,
                onShowEditHistory = if (appViewModel != null) openEditHistory else null,
                onCodeBlockClick = onCodeBlockClick,
                onShowMenu = onShowMenu
            )
        }

        // For our messages: mirror the "others" structure to the right side
        if (actualIsMine && !isConsecutive && !isEmoteMessage) {
            // Gap between bubble and avatar (same as AvatarGap)
            Spacer(modifier = Modifier.width(AvatarGap))
            // Avatar with timestamp below it on the right side, same width as others
            Column(
                modifier = Modifier.width(AvatarColumnWidth),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.clickable { onUserClick(event.sender) }) {
                    // Don't use shared element for timeline message avatars
                    AvatarImage(
                        mxcUrl = avatarUrl,
                        homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                        authToken = authToken,
                        fallbackText = (displayName ?: event.sender).take(1),
                        size = 24.dp,
                        userId = event.sender,
                        displayName = displayName,
                        isVisible = true
                    )
                }
                val timestampText = if (editedBy != null) {
                    "${formatTimestamp(event.timestamp)} (edited at ${formatTimestamp(editedBy.timestamp)})"
                } else {
                    formatTimestamp(event.timestamp)
                }
                val timestampModifier = if (editedBy != null && appViewModel != null) {
                    Modifier.clickable { openEditHistory() }
                } else {
                    Modifier
                }
                Text(
                    text = timestampText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = timestampModifier
                )
            }
            // Trailing gap to mirror the left-side inset on others
            Spacer(modifier = Modifier.width(AvatarGap))
        } else if (actualIsMine && isConsecutive && !isEmoteMessage) {
            // Maintain the same footprint (AvatarColumnWidth + AvatarGap) for consecutive own messages
            Spacer(modifier = Modifier.width(AvatarGap))
            Box(
                modifier = Modifier.width(AvatarPlaceholderWidth),
                contentAlignment = Alignment.BottomCenter
            ) {
                val timestampText = if (editedBy != null) {
                    "${formatTimestamp(event.timestamp)} (edited at ${formatTimestamp(editedBy.timestamp)})"
                } else {
                    formatTimestamp(event.timestamp)
                }
                val timestampModifier = if (editedBy != null && appViewModel != null) {
                    Modifier.clickable { openEditHistory() }
                } else {
                    Modifier
                }
                Text(
                    text = timestampText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = timestampModifier
                )
            }
        }
    }

    if (showEditHistoryDialog) {
        when {
            editHistoryLoading -> {
                AlertDialog(
                    onDismissRequest = { if (!editHistoryLoading) showEditHistoryDialog = false },
                    confirmButton = {},
                    title = { Text("Loading edit history") },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ExpressiveLoadingIndicator(modifier = Modifier.size(24.dp))
                            Text("Fetching the latest edits…")
                        }
                    }
                )
            }
            editHistoryVersion != null -> {
                EditHistoryDialog(
                    versioned = editHistoryVersion!!,
                    onDismiss = { showEditHistoryDialog = false }
                )
            }
            else -> {
                val errorMessage = editHistoryError ?: "Edit history is not available"
                AlertDialog(
                    onDismissRequest = { showEditHistoryDialog = false },
                    confirmButton = {
                        TextButton(onClick = { showEditHistoryDialog = false }) {
                            Text("OK")
                        }
                    },
                    title = { Text("Edit history unavailable") },
                    text = { Text(errorMessage) }
                )
            }
        }
    }
}

private fun Modifier.reactionHorizontalInset(
    isMine: Boolean,
    inset: Dp
): Modifier {
    if (inset == 0.dp) return this
    return this.padding(
        start = if (!isMine) inset else 0.dp,
        end = if (isMine) inset else 0.dp
    )
}

private fun String?.containsSpoilerMarkers(): Boolean {
    if (this.isNullOrEmpty()) return false
    val lower = this.lowercase()
    return lower.contains("hicli-spoiler") ||
        lower.contains("data-mx-spoiler") ||
        this.contains("||")
}

private fun TimelineEvent.containsSpoilerContent(): Boolean {
    return content?.optString("formatted_body").containsSpoilerMarkers() ||
        decrypted?.optString("formatted_body").containsSpoilerMarkers() ||
        localContent?.optString("sanitized_html").containsSpoilerMarkers() ||
        extractSanitizedHtml(this).containsSpoilerMarkers() ||
        content?.optString("body").containsSpoilerMarkers() ||
        decrypted?.optString("body").containsSpoilerMarkers() ||
        localContent?.optString("edit_source").containsSpoilerMarkers()
}