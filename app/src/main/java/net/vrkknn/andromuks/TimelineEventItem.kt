package net.vrkknn.andromuks

import android.util.Log
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.utils.AnimatedInlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.EmoteEventNarrator
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.utils.MessageBubbleWithMenu
import net.vrkknn.andromuks.utils.ReactionBadges
import net.vrkknn.andromuks.utils.ReplyPreview
import net.vrkknn.andromuks.utils.StickerMessage
import net.vrkknn.andromuks.utils.SystemEventNarrator
import net.vrkknn.andromuks.utils.extractStickerFromEvent
import net.vrkknn.andromuks.utils.supportsHtmlRendering
import net.vrkknn.andromuks.utils.RedactionUtils
import net.vrkknn.andromuks.utils.RoomLink

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    Log.d("Andromuks", "isMentioningUser: Found mention of $userId in event ${event.eventId}")
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
                    Log.d("Andromuks", "isMentioningUser: Found mention of $userId in encrypted event ${event.eventId}")
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

@Composable
fun InlineBubbleTimestamp(
    timestamp: Long,
    editedBy: TimelineEvent? = null,
    isMine: Boolean,
    isConsecutive: Boolean
) {
    // Only show timestamp inside bubble for consecutive messages
    if (isConsecutive) {
        Text(
            text =
                if (editedBy != null) {
                    " ${formatTimestamp(timestamp)} (edited)"
                } else {
                    " ${formatTimestamp(timestamp)}"
                },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
    onRoomLinkClick: (RoomLink) -> Unit = {}
) {
    // For redacted messages, always use plain text to show the deletion message
    val isRedacted = event.redactedBy != null
    
    // Check if HTML rendering is supported and available (and not redacted)
    if (!isRedacted && supportsHtmlRendering(event)) {
        Log.d("Andromuks", "AdaptiveMessageText: Using HTML rendering for event ${event.eventId}")
        HtmlMessageText(
            event = event,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            color = textColor,
            modifier = modifier,
            onMatrixUserClick = onMatrixUserClick,
            onRoomLinkClick = onRoomLinkClick,
            appViewModel = appViewModel
        )
    } else {
        // Fallback to plain text for redacted messages or when HTML is not available
        if (isRedacted) {
            Log.d("Andromuks", "AdaptiveMessageText: Using plain text for redacted event ${event.eventId}")
        } else {
            Log.d("Andromuks", "AdaptiveMessageText: Using SmartMessageText for event ${event.eventId}")
        }
        
        // Show deletion message (in body parameter) or regular text
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontStyle = if (isRedacted) FontStyle.Italic else FontStyle.Normal,
            modifier = modifier
        )
    }
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
    onBubbleClick: (() -> Unit)? = null
) {
    // Check if this is a thread message
    val isThreadMessage = event.isThreadMessage()
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Show nested reply preview only for non-thread messages
        // Thread messages are rendered without nested reply (different color instead)
        if (replyInfo != null && originalEvent != null && !isThreadMessage) {
            Column {
                ReplyPreview(
                    replyInfo = replyInfo,
                    originalEvent = originalEvent,
                    userProfileCache = userProfileCache,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    isMine = isMine,
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
                    onBubbleClick = onBubbleClick
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
                onBubbleClick = onBubbleClick
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
    onThreadClick: (TimelineEvent) -> Unit
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
                onThreadClick = onThreadClick
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
                onThreadClick = onThreadClick
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
                onThreadClick = onThreadClick
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
    onThreadClick: (TimelineEvent) -> Unit
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
    
    // Handle m.emote messages with narrator rendering
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
            
            // Add reaction badges for emote messages
            if (appViewModel != null) {
                val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                    appViewModel.messageReactions[event.eventId] ?: emptyList()
                }
                if (reactions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 28.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        ReactionBadges(
                            eventId = event.eventId,
                            reactions = reactions,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
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
    
    val redactionSender = redactionEvent?.sender

    // Request profile if redaction sender is missing from cache
    if (isRedacted && redactionSender != null && appViewModel != null) {
        if (!userProfileCache.containsKey(redactionSender)) {
            android.util.Log.d(
                "Andromuks",
                "RoomTimelineScreen: Requesting profile for redaction sender: $redactionSender in room ${event.roomId}"
            )
            appViewModel.requestUserProfile(redactionSender, event.roomId)
        }
    }

    // Show deletion message if redacted, otherwise show the message content
    val finalBody =
        if (isRedacted) {
            // OPTIMIZED: Create deletion message using O(1) cached redaction event
            net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                redactionEvent,
                userProfileCache
            )
        } else {
            body // Show the message content (for edit events, this is already the
                 // new content)
        }

    // Check if this is a reply message
    val replyInfo = event.getReplyInfo()
    val originalEvent =
        replyInfo?.let { reply ->
            timelineEvents.find<TimelineEvent> { it.eventId == reply.eventId }
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
            onThreadClick = onThreadClick
        )
    } else {
        RoomTextMessageContent(
            event = event,
            finalBody = finalBody,
            format = format,
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
            onScrollToMessage = onScrollToMessage,
            onReply = onReply,
            onReact = onReact,
            onEdit = onEdit,
            onDelete = onDelete,
            onUserClick = onUserClick,
            onRoomLinkClick = onRoomLinkClick,
            onThreadClick = onThreadClick
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
    onThreadClick: (TimelineEvent) -> Unit
) {
    Log.d(
        "Andromuks",
        "TimelineEventItem: Found media message - msgType=$msgType, body=$body"
    )

    // If media message is redacted, show deletion message instead of media
    if (isRedacted) {
        // OPTIMIZED: Display deletion message for media using cached redaction event
        val deletionMessage =
            net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                redactionEvent,
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

        val bubbleColor =
            if (actualIsMine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        val textColor =
            if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                if (actualIsMine) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                color = bubbleColor,
                shape = bubbleShape,
                shadowElevation = 3.dp,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = deletionMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    fontStyle =
                        FontStyle.Italic, // Make deletion messages italic
                    modifier =
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
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

    Log.d(
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
            info.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }

        // Extract thumbnail info for videos
        var thumbnailIsEncrypted = false
        val thumbnailUrl = if (msgType == "m.video") {
            val thumbnailFile = info.optJSONObject("thumbnail_file")
            if (thumbnailFile != null) {
                thumbnailIsEncrypted = true
                thumbnailFile.optString("url", "")?.takeIf { it.isNotBlank() }
            } else {
                info.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
            }
        } else null
        
        val thumbnailInfo = if (msgType == "m.video") {
            info.optJSONObject("thumbnail_info")
        } else null
        
        val thumbnailBlurHash = thumbnailInfo?.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
        val thumbnailWidth = thumbnailInfo?.optInt("w", 0)
        val thumbnailHeight = thumbnailInfo?.optInt("h", 0)
        val duration = if (msgType == "m.video" || msgType == "m.audio") {
            info.optInt("duration", 0).takeIf { it > 0 }
        } else null

        // Extract caption
        val caption = if (body != filename && body.isNotBlank()) {
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
                thumbnailIsEncrypted = thumbnailIsEncrypted
            )

        val mediaMessage =
            MediaMessage(
                url = url,
                filename = filename,
                caption = caption,
                info = mediaInfo,
                msgType = msgType
            )

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
            }
        )

        // Add reaction badges for media messages
        if (appViewModel != null) {
            val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                appViewModel.messageReactions[event.eventId] ?: emptyList()
            }
            if (reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement =
                        if (actualIsMine) Arrangement.End else Arrangement.Start
                ) {
                    ReactionBadges(
                        eventId = event.eventId,
                        reactions = reactions,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
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
        
        val bubbleColor =
            if (actualIsMine) MaterialTheme.colorScheme.primaryContainer
            else if (mentionsMe) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        val textColor =
            if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer
            else if (mentionsMe) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                if (actualIsMine) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                color = bubbleColor,
                shape = bubbleShape,
                shadowElevation = 3.dp,
                modifier = Modifier.padding(top = 4.dp)
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
    onScrollToMessage: (String) -> Unit,
    onReply: (TimelineEvent) -> Unit,
    onReact: (TimelineEvent) -> Unit,
    onEdit: (TimelineEvent) -> Unit,
    onDelete: (TimelineEvent) -> Unit,
    onUserClick: (String) -> Unit,
    onRoomLinkClick: (RoomLink) -> Unit,
    onThreadClick: (TimelineEvent) -> Unit
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
    
    // Check if message has been edited (O(1) lookup)
    val hasBeenEdited = remember(event.eventId, appViewModel?.timelineUpdateCounter) {
        appViewModel?.isMessageEdited(event.eventId) ?: false
    }
    
    // Check if this is a thread message
    val isThreadMessage = event.isThreadMessage()
    
    val bubbleColor =
        if (isThreadMessage) {
            if (actualIsMine) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        } else if (actualIsMine) {
            if (hasBeenEdited) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        } else if (mentionsMe) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            if (hasBeenEdited) MaterialTheme.colorScheme.surfaceContainerHighest
            else MaterialTheme.colorScheme.surfaceVariant
        }
    val textColor =
        if (isThreadMessage) {
            MaterialTheme.colorScheme.tertiary
        } else if (actualIsMine) {
            if (hasBeenEdited) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onPrimaryContainer
        } else if (mentionsMe) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

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
                onUserClick = onUserClick
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Display reply with nested structure if this is a reply (but NOT a thread message)
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
                }
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    // Reply preview
                    ReplyPreview(
                        replyInfo = replyInfo,
                        originalEvent = originalEvent,
                        userProfileCache = userProfileCache,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isMine = actualIsMine,
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
                    Box {
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
                            modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier,
                            onUserClick = onUserClick,
                            onMatrixUserClick = onUserClick,
                            onRoomLinkClick = onRoomLinkClick
                        )
                        // Timestamp positioned at bottom-end
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd)
                        ) {
                            InlineBubbleTimestamp(
                                timestamp = event.timestamp,
                                editedBy = editedBy,
                                isMine = actualIsMine,
                                isConsecutive = isConsecutive
                            )
                        }
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
                }
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    // Text with extra padding at the end for timestamp
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
                        modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier,
                        onUserClick = onUserClick,
                        onMatrixUserClick = onUserClick,
                        onRoomLinkClick = onRoomLinkClick
                    )
                    // Timestamp positioned at bottom-end
                    Box(
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        InlineBubbleTimestamp(
                            timestamp = event.timestamp,
                            editedBy = editedBy,
                            isMine = actualIsMine,
                            isConsecutive = isConsecutive
                        )
                    }
                }
            }
        }

        // For others' messages, show read receipts on the right of the bubble
        if (!actualIsMine && readReceipts.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            AnimatedInlineReadReceiptAvatars(
                receipts = readReceipts,
                userProfileCache = userProfileCache,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                appViewModel = appViewModel,
                messageSender = event.sender,
                eventId = event.eventId,
                roomId = event.roomId,
                onUserClick = onUserClick
            )
        }
    }

    // Add reaction badges for this message
    if (appViewModel != null) {
        val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
            appViewModel.messageReactions[event.eventId] ?: emptyList()
        }
        if (reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement =
                    if (actualIsMine) Arrangement.End else Arrangement.Start
            ) {
                ReactionBadges(
                    eventId = event.eventId,
                    reactions = reactions,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    onReactionClick = { emoji ->
                        appViewModel?.sendReaction(event.roomId, event.eventId, emoji)
                    }
                )
            }
        }
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
    onThreadClick: (TimelineEvent) -> Unit
) {
    // Check if this is an edit event (m.replace relationship) - don't display edit events
    val isEditEvent =
        event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") ==
            "m.replace"
    if (isEditEvent) {
        android.util.Log.d(
            "Andromuks",
            "RoomTimelineScreen: Filtering out edit event ${event.eventId}"
        )
        return // Don't display edit events as separate timeline items
    }

    val decryptedType = event.decryptedType
    val decrypted = event.decrypted
    if (decryptedType == "m.room.message") {
        val format = decrypted?.optString("format", "")
        val body =
            if (format == "org.matrix.custom.html") {
                decrypted?.optString("formatted_body", "") ?: ""
            } else {
                decrypted?.optString("body", "") ?: ""
            }
        android.util.Log.d(
            "Andromuks",
            "RoomTimelineScreen: Displaying encrypted event ${event.eventId} with body: '$body'"
        )
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
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 28.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            ReactionBadges(
                                eventId = event.eventId,
                                reactions = reactions,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
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
        
        val redactionSender = redactionEvent?.sender

        // Request profile if redaction sender is missing from cache
        if (isRedacted && redactionSender != null && appViewModel != null) {
            if (!userProfileCache.containsKey(redactionSender)) {
                android.util.Log.d(
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
                // OPTIMIZED: Create deletion message using O(1) cached redaction event
                net.vrkknn.andromuks.utils.RedactionUtils.createDeletionMessageFromEvent(
                    redactionEvent,
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

        // Check if this is a reply message
        val replyInfo = event.getReplyInfo()
        val originalEvent =
            replyInfo?.let { reply ->
                timelineEvents.find<TimelineEvent> { it.eventId == reply.eventId }
            }

        // Check if it's a media message
        if (msgType == "m.image" || msgType == "m.video" || msgType == "m.audio" || msgType == "m.file") {
            Log.d(
                "Andromuks",
                "TimelineEventItem: Found encrypted media message - msgType=$msgType, body=$body"
            )

            // Debug: Check what's in the decrypted object
            Log.d(
                "Andromuks",
                "TimelineEventItem: Direct url field: ${decrypted?.optString("url", "NOT_FOUND")}"
            )
            Log.d(
                "Andromuks",
                "TimelineEventItem: File object exists: ${decrypted?.has("file")}"
            )
            if (decrypted?.has("file") == true) {
                val fileObj = decrypted.optJSONObject("file")
                Log.d(
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

            Log.d(
                "Andromuks",
                "TimelineEventItem: URL extraction - directUrl='$directUrl', fileObj=${fileObj != null}, fileUrl='$fileUrl', finalUrl='$url', hasEncryptedFile=$hasEncryptedFile"
            )

            val filename = decrypted?.optString("filename", "") ?: ""
            val info = decrypted?.optJSONObject("info")

            Log.d(
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

                // Extract thumbnail info for encrypted videos
                // For encrypted media, thumbnail is in thumbnail_file.url, not thumbnail_url
                var thumbnailIsEncrypted = false
                val thumbnailUrl = if (msgType == "m.video") {
                    val thumbnailFile = info.optJSONObject("thumbnail_file")
                    if (thumbnailFile != null) {
                        // Encrypted thumbnail
                        thumbnailIsEncrypted = true
                        thumbnailFile.optString("url", "")?.takeIf { it.isNotBlank() }
                    } else {
                        // Unencrypted thumbnail (fallback, though unlikely for encrypted messages)
                        info.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
                    }
                } else null
                
                val thumbnailInfo = if (msgType == "m.video") {
                    info.optJSONObject("thumbnail_info")
                } else null
                
                val thumbnailBlurHash = thumbnailInfo?.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
                val thumbnailWidth = thumbnailInfo?.optInt("w", 0)
                val thumbnailHeight = thumbnailInfo?.optInt("h", 0)
                val duration = if (msgType == "m.video" || msgType == "m.audio") {
                    info.optInt("duration", 0).takeIf { it > 0 }
                } else null

                // Extract caption: use sanitized_html if available, otherwise body (only if different from filename)
                val caption = if (body != filename && body.isNotBlank()) {
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
                        thumbnailIsEncrypted = thumbnailIsEncrypted
                    )

                val mediaMessage =
                    MediaMessage(
                        url = url,
                        filename = filename,
                        caption = caption,
                        info = mediaInfo,
                        msgType = msgType
                    )

                Log.d(
                    "Andromuks",
                    "TimelineEventItem: Created encrypted MediaMessage - url=${mediaMessage.url}, blurHash=${mediaMessage.info.blurHash}"
                )

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
                    onBubbleClick = if (event.isThreadMessage()) {
                        { onThreadClick(event) }
                    } else {
                        null
                    }
                )

                // Add reaction badges for encrypted media messages
                if (appViewModel != null) {
                    val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                        appViewModel.messageReactions[event.eventId] ?: emptyList()
                    }
                    if (reactions.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement =
                                if (actualIsMine) Arrangement.End
                                else Arrangement.Start
                        ) {
                            ReactionBadges(
                                eventId = event.eventId,
                                reactions = reactions,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
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
                
                val bubbleColor =
                    if (actualIsMine) MaterialTheme.colorScheme.primaryContainer
                    else if (mentionsMe) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                val textColor =
                    if (actualIsMine) MaterialTheme.colorScheme.onPrimaryContainer
                    else if (mentionsMe) MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant

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
                        onBubbleClick = null
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
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement =
                                if (actualIsMine) Arrangement.End
                                else Arrangement.Start
                        ) {
                            ReactionBadges(
                                eventId = event.eventId,
                                reactions = reactions,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
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
            
            // Check if message has been edited (O(1) lookup)
            val hasBeenEdited = remember(event.eventId, appViewModel?.timelineUpdateCounter) {
                appViewModel?.isMessageEdited(event.eventId) ?: false
            }
            
            // Check if this is a thread message
            val isThreadMessage = event.isThreadMessage()
            
            val bubbleColor =
                if (isThreadMessage) {
                    // Thread messages use Material3 tertiary colors (typically purple/violet)
                    // Own messages: fuller opacity for emphasis
                    // Others' messages: lighter for distinction
                    if (actualIsMine) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                } else if (actualIsMine) {
                    if (hasBeenEdited) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.primaryContainer
                } else if (mentionsMe) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    if (hasBeenEdited) MaterialTheme.colorScheme.surfaceContainerHighest
                    else MaterialTheme.colorScheme.surfaceVariant
                }
            val textColor =
                if (isThreadMessage) {
                    // Thread messages use tertiary color for text
                    MaterialTheme.colorScheme.tertiary
                } else if (actualIsMine) {
                    if (hasBeenEdited) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer
                } else if (mentionsMe) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

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
                        onUserClick = onUserClick
                    )
                    Spacer(modifier = Modifier.width(8.dp))
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
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            // Reply preview (clickable original message)
                            ReplyPreview(
                                replyInfo = replyInfo,
                                originalEvent = originalEvent,
                                userProfileCache = userProfileCache,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                isMine = actualIsMine,
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
                            Box {
                                // finalBody already contains deletion message if redacted
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
                                    modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier,
                                    onUserClick = onUserClick,
                                    onMatrixUserClick = onUserClick,
                                    onRoomLinkClick = onRoomLinkClick
                                )
                                // Timestamp positioned at bottom-end
                                Box(
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                ) {
                                    InlineBubbleTimestamp(
                                        timestamp = event.timestamp,
                                        editedBy = editedBy,
                                        isMine = actualIsMine,
                                        isConsecutive = isConsecutive
                                    )
                                }
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
                        }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            // finalBody already contains deletion message if redacted
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
                                modifier = if (isConsecutive) Modifier.padding(end = 48.dp) else Modifier,
                                onUserClick = onUserClick,
                                onMatrixUserClick = onUserClick,
                                onRoomLinkClick = onRoomLinkClick
                            )
                            // Timestamp positioned at bottom-end
                            Box(
                                modifier = Modifier.align(Alignment.BottomEnd)
                            ) {
                                InlineBubbleTimestamp(
                                    timestamp = event.timestamp,
                                    editedBy = editedBy,
                                    isMine = actualIsMine,
                                    isConsecutive = isConsecutive
                                )
                            }
                        }
                    }
                }

                    // For others' messages, show read receipts on the right of the bubble
                    if (!actualIsMine && readReceipts.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AnimatedInlineReadReceiptAvatars(
                            receipts = readReceipts,
                            userProfileCache = userProfileCache,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            appViewModel = appViewModel,
                            messageSender = event.sender,
                            eventId = event.eventId,
                            roomId = event.roomId,
                            onUserClick = onUserClick
                        )
                }
            }

            // Add reaction badges for encrypted text message
            if (appViewModel != null) {
                val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                    appViewModel.messageReactions[event.eventId] ?: emptyList()
                }
                if (reactions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement =
                            if (actualIsMine) Arrangement.End else Arrangement.Start
                    ) {
                        ReactionBadges(
                            eventId = event.eventId,
                            reactions = reactions,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
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
    onThreadClick: (TimelineEvent) -> Unit
) {
    val stickerMessage = extractStickerFromEvent(event)

    if (stickerMessage != null) {
        Log.d(
            "Andromuks",
            "TimelineEventItem: Found sticker - url=${stickerMessage.url}, body=${stickerMessage.body}, dimensions=${stickerMessage.width}x${stickerMessage.height}"
        )

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
                    onUserClick = onUserClick
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            StickerMessage(
                stickerMessage = stickerMessage,
                homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                authToken = authToken,
                isMine = actualIsMine,
                isEncrypted = stickerMessage.hasEncryptedFile,
                event = event,
                timestamp = event.timestamp,
                isConsecutive = false,
                onReply = { onReply(event) },
                onReact = { onReact(event) },
                onEdit = { onEdit(event) },
                onDelete = { onDelete(event) },
                myUserId = myUserId,
                powerLevels = appViewModel?.currentRoomState?.powerLevels,
                appViewModel = appViewModel,
                onBubbleClick = if (event.isThreadMessage()) {
                    { onThreadClick(event) }
                } else {
                    null
                }
            )

            // For other users' messages, show read receipts on the right
            if (!actualIsMine && readReceipts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                AnimatedInlineReadReceiptAvatars(
                    receipts = readReceipts,
                    userProfileCache = userProfileCache,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    appViewModel = appViewModel,
                    messageSender = event.sender,
                    eventId = event.eventId,
                    roomId = event.roomId,
                    onUserClick = onUserClick
                )
            }
        }

        // Add reaction badges for stickers
        if (appViewModel != null) {
            val reactions = remember(appViewModel.reactionUpdateCounter, event.eventId) {
                appViewModel.messageReactions[event.eventId] ?: emptyList()
            }
            if (reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement =
                        if (actualIsMine) Arrangement.End else Arrangement.Start
                ) {
                    ReactionBadges(
                        eventId = event.eventId,
                        reactions = reactions,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
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
    onScrollToMessage: (String) -> Unit = {},
    onReply: (TimelineEvent) -> Unit = {},
    onReact: (TimelineEvent) -> Unit = {},
    onEdit: (TimelineEvent) -> Unit = {},
    onDelete: (TimelineEvent) -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onRoomLinkClick: (RoomLink) -> Unit = {},
    onThreadClick: (TimelineEvent) -> Unit = {}
) {
    val context = LocalContext.current
    
    // OPPORTUNISTIC PROFILE LOADING: Request profile only when this event is actually rendered
    LaunchedEffect(event.sender, event.roomId) {
        if (appViewModel != null && event.sender != myUserId) {
            // Check if we already have the profile
            val existingProfile = appViewModel.getUserProfile(event.sender, event.roomId)
            if (existingProfile == null) {
                Log.d("Andromuks", "TimelineEventItem: Requesting profile on-demand for ${event.sender}")
                appViewModel.requestUserProfileOnDemand(event.sender, event.roomId)
            }
        }
    }

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
    val mentionsMe = !actualIsMine && isMentioningUser(event, myUserId)

    // Check if this is a narrator event (system event)
    val isNarratorEvent =
        event.type in
            setOf(
                "m.room.member",
                "m.room.name",
                "m.room.topic",
                "m.room.avatar",
                "m.room.pinned_events"
            )

    // Check if this message is being edited by another event (moved to function start)
    val editedBy =
        timelineEvents.find {
            (it.content?.optJSONObject("m.relates_to")?.optString("event_id") == event.eventId &&
                it.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace") ||
                (it.decrypted?.optJSONObject("m.relates_to")?.optString("event_id") ==
                    event.eventId &&
                    it.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") ==
                        "m.replace")
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
            onUserClick = onUserClick
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
    val readReceipts =
        remember(event.eventId, appViewModel?.readReceiptsUpdateCounter) {
            if (appViewModel != null) {
                net.vrkknn.andromuks.utils.ReceiptFunctions.getReadReceipts(
                    event.eventId,
                    appViewModel.getReadReceiptsMap()
                )
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
    
    // Check if this message should animate in (new message with slide-up effect)
    val newMessageAnimations = appViewModel?.getNewMessageAnimations() ?: emptyMap()
    val animationCompletionTime = newMessageAnimations[event.eventId]
    val shouldAnimate = animationCompletionTime != null
    val animationProgress = remember(event.eventId) {
        Animatable(if (shouldAnimate) 0f else 1f)
    }

    // Launch animation when this message is marked for animation
    LaunchedEffect(event.eventId, animationCompletionTime) {
        try {
            if (!shouldAnimate) {
                animationProgress.snapTo(1f)
            } else {
                animationProgress.snapTo(0f)
                val startTime = animationCompletionTime!! - AppViewModel.NEW_MESSAGE_ANIMATION_DURATION_MS
                val delayMs = startTime - System.currentTimeMillis()
                if (delayMs > 0) {
                    kotlinx.coroutines.delay(delayMs)
                }
                animationProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = AppViewModel.NEW_MESSAGE_ANIMATION_DURATION_MS.toInt(),
                        easing = FastOutSlowInEasing
                    )
                )
            }
        } finally {
            appViewModel?.notifyMessageAnimationFinished(event.eventId)
        }
    }

    // Animate slide-up effect for new messages
    val animatedOffsetY = if (shouldAnimate) {
        val eased = FastOutSlowInEasing.transform(animationProgress.value)
        (1f - eased) * 120f
    } else {
        0f
    }
    
    // Animate alpha for smooth fade-in
    val animatedAlpha = if (shouldAnimate) {
        0.3f + 0.7f * animationProgress.value
    } else {
        1f
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .graphicsLayer(
                translationY = animatedOffsetY,
                alpha = animatedAlpha
            ),
        verticalAlignment = Alignment.Top
    ) {
        // Show avatar only for non-consecutive messages (and not for emotes, they have their own)
        if (!actualIsMine && !isConsecutive && !isEmoteMessage) {
            Box(modifier = Modifier.clickable { onUserClick(event.sender) }) {
                AvatarImage(
                    mxcUrl = avatarUrl,
                    homeserverUrl = appViewModel?.homeserverUrl ?: homeserverUrl,
                    authToken = authToken,
                    fallbackText = (displayName ?: event.sender).take(1),
                    size = 24.dp,
                    userId = event.sender,
                    displayName = displayName,
                    // AVATAR LOADING OPTIMIZATION: Enable lazy loading for timeline performance
                    isVisible = true // Timeline items are visible when rendered
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        } else if (!actualIsMine && isConsecutive && !isEmoteMessage) {
            // Add spacer to maintain alignment for consecutive messages (but not for emotes)
            Spacer(modifier = Modifier.width(32.dp)) // 24dp avatar + 8dp spacer
        }

        // Event content
        Column(modifier = Modifier.weight(1f)) {
            // Show name and timestamp header only for non-consecutive messages (and not for emotes)
            if (!isConsecutive && !isEmoteMessage) {
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text =
                            if (editedBy != null) {
                                "${formatTimestamp(event.timestamp)} (edited at ${formatTimestamp(editedBy.timestamp)})"
                            } else {
                                formatTimestamp(event.timestamp)
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                onThreadClick = onThreadClick
            )

        if (actualIsMine) {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
}