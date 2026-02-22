package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.SingleEventRendererDialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toIntRect
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TagFaces
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReplyInfo
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.LocalScrollHighlightState
import net.vrkknn.andromuks.utils.RedactionUtils
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.supportsHtmlRendering
import net.vrkknn.andromuks.RoomTimelineCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext



/**
 * Displays a reply preview showing the original message being replied to.
 * 
 * This function renders a nested bubble structure where the replied-to message
 * is displayed in an inner bubble within the main reply bubble. The original
 * message is clickable and will scroll to the original message when tapped.
 * The preview shows the sender's name and a truncated version of the original
 * message content.
 * 
 * If the original message has been redacted/deleted, it will show a deletion
 * message instead of the original content, following the same format as the
 * main timeline deletion messages.
 * 
 * @param replyInfo ReplyInfo object containing sender and eventId of the original message
 * @param originalEvent TimelineEvent object of the original message being replied to
 * @param userProfileCache Map of user IDs to MemberProfile objects for display names
 * @param homeserverUrl Base URL of the Matrix homeserver (unused but kept for consistency)
 * @param authToken Authentication token (unused but kept for consistency)
 * @param previewColors Bubble color palette for the original message (used for tinting)
 * @param modifier Modifier to apply to the reply preview container
 * @param onOriginalMessageClick Callback function called when the original message is clicked
 */
@Composable
fun ReplyPreview(
    replyInfo: ReplyInfo,
    originalEvent: TimelineEvent?,
    userProfileCache: Map<String, MemberProfile>,
    homeserverUrl: String,
    authToken: String,
    previewColors: BubbleColors,
    modifier: Modifier = Modifier,
    onOriginalMessageClick: () -> Unit = {},
    timelineEvents: List<TimelineEvent> = emptyList(),
    onMatrixUserClick: (String) -> Unit = {},
    appViewModel: net.vrkknn.andromuks.AppViewModel? = null
) {
    // OPTIMIZED: Use version cache if available, otherwise fall back to chain resolution
    val latestOriginalEvent = if (appViewModel != null && originalEvent != null) {
        appViewModel.getLatestMessageVersion(originalEvent.eventId) ?: originalEvent
    } else {
        originalEvent?.let { event ->
            RedactionUtils.resolveEventChain(event.eventId, timelineEvents)
        }
    }
    
    val originalSender = latestOriginalEvent?.sender ?: replyInfo.sender
    val originalBody = latestOriginalEvent?.let { event ->
        // OPTIMIZED: Check if redacted using O(1) lookup
        if (event.redactedBy != null) {
            // Latest version was deleted - use O(1) cached redaction event
            val redactionEvent = appViewModel?.getRedactionEvent(event.eventId)
            if (redactionEvent != null) {
                RedactionUtils.createDeletionMessageFromEvent(redactionEvent, userProfileCache)
            } else {
                // Fallback to scanning if cache unavailable
                RedactionUtils.createDeletionMessageForEvent(event, timelineEvents, userProfileCache)
            }
        } else {
            // Latest version is still available - show its content
            when {
                event.type == "m.room.message" -> event.content?.optString("body", "")
                event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> {
                    // For encrypted messages, check if it's an edit
                    val isEdit = event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                    if (isEdit) {
                        // This is an edit, show the new content
                        event.decrypted?.optJSONObject("m.new_content")?.optString("body", "")
                    } else {
                        // Regular encrypted message
                        event.decrypted?.optString("body", "")
                    }
                }
                else -> {
                    // For non-message events (system events, stickers, etc.), use the formatting function
                    // This handles all event types including joins, leaves, ACL changes, etc.
                    formatEventForReplyPreview(event, appViewModel, event.roomId)
                }
            }
        }
    } ?: "Reply to unknown event"
    
    val memberProfile = userProfileCache[originalSender]
    val senderName = memberProfile?.displayName ?: originalSender
    
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .wrapContentWidth(Alignment.Start),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                .background(previewColors.stripe)
        )
        Surface(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start)
                .clickable { onOriginalMessageClick() },
            shape = RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 10.dp,
                bottomEnd = 10.dp,
                bottomStart = 0.dp
            ),
            color = BubblePalette.replyPreviewBackground(colorScheme, previewColors),
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, previewColors.stripe.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = previewColors.accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )

                if (latestOriginalEvent != null && supportsHtmlRendering(latestOriginalEvent)) {
                    HtmlMessageText(
                        event = latestOriginalEvent,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        color = previewColors.content,
                        modifier = Modifier,
                        onMatrixUserClick = onMatrixUserClick,
                        appViewModel = appViewModel
                    )
                } else {
                    Text(
                        text = originalBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = previewColors.content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 0.9
                    )
                }
            }
        }
    }
}

/**
 * Modifier that adds popup menu functionality to any message bubble.
 * Can be applied to existing Surface components to add React, Reply, Edit, Delete options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.messageBubbleMenu(
    event: TimelineEvent,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
): Modifier {
    var showMenu by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorDialogText by remember { mutableStateOf<String?>(null) }
    var showDeletedDialog by remember { mutableStateOf(false) }
    var deletedDialogText by remember { mutableStateOf<String?>(null) }
    var deletedReason by remember { mutableStateOf<String?>(null) }
    
    return this
        .clickable { 
            if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleMenu: Click detected, showing menu")
            showMenu = !showMenu 
        }
        .then(
            Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { 
                        if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleMenu: Long press detected")
                        showMenu = true 
                    },
                    onDragEnd = { 
                        if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleMenu: Drag end")
                        showMenu = false 
                    },
                    onDrag = { _, _ -> }
                )
            }
        )
        .then(
            Modifier.background(androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.1f)) // Temporary debug background
        )
}

/**
 * Formats an event for display in a reply preview.
 * Handles all event types including messages, stickers, system events, etc.
 * 
 * @param event The timeline event to format
 * @param appViewModel Optional AppViewModel for accessing user profiles
 * @param roomId Optional room ID for profile lookups
 * @return A string description of the event suitable for reply preview
 */
fun formatEventForReplyPreview(
    event: TimelineEvent,
    appViewModel: net.vrkknn.andromuks.AppViewModel? = null,
    roomId: String? = null
): String {
    val eventType = event.type
    // CRITICAL FIX: For encrypted events, prioritize decrypted content over encrypted content
    // For encrypted messages, event.content contains encrypted data, not the actual message body
    val content = if (eventType == "m.room.encrypted" && event.decrypted != null) {
        event.decrypted
    } else {
        event.content ?: event.decrypted
    }
    
    return when (eventType) {
        "m.room.message", "m.room.encrypted" -> {
            // Handle message events
            // For encrypted events, check decryptedType first
            val actualMsgType = if (eventType == "m.room.encrypted" && event.decryptedType != null) {
                // For encrypted events, use decryptedType to determine the message type
                when (event.decryptedType) {
                    "m.sticker" -> "m.sticker"
                    else -> content?.optString("msgtype", "") ?: ""
                }
            } else {
                content?.optString("msgtype", "") ?: ""
            }
            // For encrypted messages, check if it's an edit (m.replace relationship)
            val isEdit = eventType == "m.room.encrypted" && 
                event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            val body = if (isEdit) {
                // For edits, get the body from m.new_content
                event.decrypted?.optJSONObject("m.new_content")?.optString("body", "") ?: ""
            } else {
                content?.optString("body", "") ?: ""
            }
            
            when (actualMsgType) {
                "m.image" -> "📷 Image"
                "m.video" -> "🎥 Video"
                "m.audio" -> "🎵 Audio"
                "m.file" -> "📎 File"
                "m.sticker" -> {
                    // Try to get sticker body/name
                    val stickerBody = body.takeIf { it.isNotBlank() } ?: "Sticker"
                    "🎨 $stickerBody"
                }
                "m.emote" -> {
                    // For emote, show the action text (body without /me prefix)
                    if (body.isNotBlank()) body else "Emote"
                }
                "m.text" -> {
                    if (body.isBlank()) "Empty message" else body
                }
                else -> {
                    // Fallback for other msgtypes or if msgtype is missing
                    if (body.isNotBlank()) body else "Message"
                }
            }
        }
        "m.sticker" -> {
            // Standalone sticker event
            val body = content?.optString("body", "") ?: ""
            val stickerBody = body.takeIf { it.isNotBlank() } ?: "Sticker"
            "🎨 $stickerBody"
        }
        "m.room.member" -> {
            // Handle member events (join, leave, ban, kick, invite, profile changes)
            val membership = content?.optString("membership", "")
            val reason = content?.optString("reason", "")
            val reasonText = if (!reason.isNullOrBlank()) ": $reason" else ""
            
            when (membership) {
                "join" -> {
                    // Check if this is a profile change vs actual join
                    val unsigned = event.unsigned
                    val prevContent = unsigned?.optJSONObject("prev_content")
                    val prevMembership = prevContent?.optString("membership", "")
                    val isProfileChange = prevContent != null && prevMembership == "join"
                    
                    if (isProfileChange) {
                        // Profile change - check what changed
                        val prevDisplayName = prevContent.optString("displayname", "")
                        val prevAvatar = prevContent.optString("avatar_url", "")
                        val currentDisplayName = content?.optString("displayname", "") ?: ""
                        val currentAvatar = content?.optString("avatar_url", "") ?: ""
                        
                        when {
                            prevDisplayName != currentDisplayName && prevAvatar != currentAvatar -> "Changed profile"
                            prevDisplayName != currentDisplayName -> "Changed display name"
                            prevAvatar != currentAvatar -> "Changed avatar"
                            else -> "Joined the room"
                        }
                    } else {
                        "Joined the room"
                    }
                }
                "leave" -> "Left the room$reasonText"
                "ban" -> {
                    val bannedUserId = event.stateKey
                    val bannedDisplayName = bannedUserId?.let { userId ->
                        appViewModel?.getUserProfile(userId, roomId)?.displayName
                    } ?: bannedUserId ?: "Unknown"
                    "Banned $bannedDisplayName$reasonText"
                }
                "kick" -> {
                    val kickedUserId = event.stateKey
                    val kickedDisplayName = kickedUserId?.let { userId ->
                        appViewModel?.getUserProfile(userId, roomId)?.displayName
                    } ?: kickedUserId ?: "Unknown"
                    "Kicked $kickedDisplayName$reasonText"
                }
                "invite" -> {
                    val invitedUserId = event.stateKey
                    val invitedDisplayName = invitedUserId?.let { userId ->
                        appViewModel?.getUserProfile(userId, roomId)?.displayName
                    } ?: invitedUserId ?: "Unknown"
                    "Invited $invitedDisplayName$reasonText"
                }
                else -> "Member event"
            }
        }
        "m.room.name" -> {
            val roomName = content?.optString("name", "") ?: ""
            if (roomName.isNotBlank()) {
                "Changed room name to \"$roomName\""
            } else {
                "Changed room name"
            }
        }
        "m.room.topic" -> "Changed room topic"
        "m.room.avatar" -> "Changed room avatar"
        "m.room.pinned_events" -> "Updated pinned events"
        "m.room.power_levels" -> "Changed power levels"
        "m.room.tombstone" -> {
            val replacementRoom = content?.optString("replacement_room", "") ?: ""
            if (replacementRoom.isNotBlank()) {
                "Replaced this room"
            } else {
                "Tombstoned this room"
            }
        }
        "m.room.server_acl" -> "Updated server ACL"
        "m.space.parent" -> "Updated space parent"
        else -> {
            // Fallback for unknown event types
            if (BuildConfig.DEBUG) {
                android.util.Log.d("ReplyPreviewInput", "Unknown event type for reply preview: $eventType")
            }
            val eventTypeStr = eventType ?: "unknown"
            "Event: ${eventTypeStr.removePrefix("m.").replace(".", " ")}"
        }
    }
}

/**
 * Reply preview component for the text input area.
 * Shows a compact preview of the message being replied to with a cancel button.
 */
@Composable
fun ReplyPreviewInput(
    event: TimelineEvent,
    userProfileCache: Map<String, MemberProfile>,
    onCancel: () -> Unit,
    appViewModel: net.vrkknn.andromuks.AppViewModel? = null,
    roomId: String? = null,
    onMatrixUserClick: (String) -> Unit = {}
) {
    // CRITICAL FIX: Use reactive member map from appViewModel if available
    // This ensures the display name updates when profiles are loaded opportunistically
    val reactiveMemberMap = if (appViewModel != null && roomId != null) {
        remember(roomId, appViewModel.memberUpdateCounter) {
            appViewModel.getMemberMap(roomId)
        }
    } else {
        null
    }
    
    // Prefer reactive member map over static userProfileCache
    val profile = reactiveMemberMap?.get(event.sender) ?: userProfileCache[event.sender]
    var displayName = profile?.displayName ?: event.sender
    
    // If we don't have a display name, try to fetch it
    var isFetchingProfile by remember { mutableStateOf(false) }
    
    if (profile?.displayName == null && appViewModel != null && roomId != null && !isFetchingProfile) {
        LaunchedEffect(event.sender) {
            if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "No display name for ${event.sender}, fetching profile...")
            isFetchingProfile = true
            // Use opportunistic profile loading (same as timeline screens)
            appViewModel.requestUserProfileOnDemand(event.sender, roomId)
            // Note: The profile will be updated via the reactive member map when the response comes back
        }
    }
    
    // Get the event to use (may be fetched if original was incomplete)
    var currentEvent = event
    var isFetchingEvent by remember { mutableStateOf(false) }
    var fetchedEvent by remember { mutableStateOf<TimelineEvent?>(null) }
    
    // Check if we need to fetch the full event (for non-message events or if content is missing)
    val content = event.content ?: event.decrypted
    val needsFullEvent = content == null || 
        (event.type !in listOf("m.room.message", "m.room.encrypted", "m.sticker") && content.optString("body", "").isBlank())
    
    if (needsFullEvent && appViewModel != null && roomId != null && !isFetchingEvent && fetchedEvent == null) {
        LaunchedEffect(event.eventId) {
            if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Missing content or non-message event, fetching full event details for: ${event.eventId}, type: ${event.type}")
            isFetchingEvent = true
            appViewModel.getEvent(roomId, event.eventId) { fullEvent ->
                isFetchingEvent = false
                if (fullEvent != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Successfully fetched event: ${fullEvent.eventId}, type: ${fullEvent.type}")
                    fetchedEvent = fullEvent
                } else {
                    android.util.Log.w("ReplyPreviewInput", "Failed to fetch event: ${event.eventId}")
                }
            }
        }
    }
    
    // Use fetched event if available
    if (fetchedEvent != null) {
        currentEvent = fetchedEvent!!
    }
    
    // Format the event description using the helper function
    val eventDescription = formatEventForReplyPreview(currentEvent, appViewModel, roomId)
    
    // CRITICAL FIX: Re-read profile from reactive member map to get latest display name
    // This ensures we show the display name once it's loaded, even if it wasn't available initially
    val latestProfile = reactiveMemberMap?.get(event.sender) ?: profile
    val finalDisplayName = latestProfile?.displayName?.takeIf { it.isNotBlank() } ?: event.sender
    
    // Final debug logging
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "=== FINAL VALUES ===")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Event sender: ${event.sender}")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Event type: ${currentEvent.type}")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Profile: $profile")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Latest profile: $latestProfile")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Display name: $finalDisplayName")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Event description: '$eventDescription'")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Fetched event null: ${fetchedEvent == null}")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Is fetching event: $isFetchingEvent")
    if (BuildConfig.DEBUG) android.util.Log.d("ReplyPreviewInput", "Is fetching profile: $isFetchingProfile")
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .padding(bottom = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp  // Use tonalElevation for dark mode visibility
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reply icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = "Replying to",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            
            // Message preview
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = finalDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = net.vrkknn.andromuks.utils.UserColorUtils.getUserColor(event.sender),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        isFetchingEvent -> "Loading message..."
                        else -> eventDescription
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Cancel button
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Edit preview shown above the text input when editing a message.
 * Displays the original message text and a cancel button.
 */
@Composable
fun EditPreviewInput(
    event: TimelineEvent,
    onCancel: () -> Unit
) {
    // Get message content - handle both encrypted and non-encrypted messages
    val content = event.content ?: event.decrypted
    val body = content?.optString("body", "") ?: ""
    val msgType = content?.optString("msgtype", "") ?: ""
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .padding(bottom = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp  // Use tonalElevation for dark mode visibility
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Edit icon
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Editing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            
            // Message preview
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Edit message",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        msgType == "m.image" -> "📷 Image"
                        msgType == "m.video" -> "🎥 Video"
                        msgType == "m.audio" -> "🎵 Audio"
                        msgType == "m.file" -> "📎 File"
                        body.isBlank() -> "Empty message"
                        else -> body
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Cancel button
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Delete message dialog with reason input and confirmation buttons.
 * Shows a dialog above the timeline for confirming message deletion with optional reason.
 */
@Composable
fun DeleteMessageDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete message",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Reason (optional):",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = { Text("Enter reason for deletion...") },
                    maxLines = 4,
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(reason)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Message bubble wrapper with popup menu functionality.
 * Provides long press/click to show menu with React, Reply, Edit, Delete options.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageBubbleWithMenu(
    event: TimelineEvent,
    bubbleColor: androidx.compose.ui.graphics.Color,
    bubbleShape: androidx.compose.foundation.shape.RoundedCornerShape,
    modifier: Modifier = Modifier,
    isMine: Boolean = false,
    myUserId: String? = null,
    powerLevels: net.vrkknn.andromuks.PowerLevelsInfo? = null,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    appViewModel: net.vrkknn.andromuks.AppViewModel? = null,
    onBubbleClick: (() -> Unit)? = null,
    onShowEditHistory: (() -> Unit)? = null,
    externalMenuTrigger: Int = 0, // External trigger to show menu (increment to trigger)
    mentionBorder: androidx.compose.ui.graphics.Color? = null, // Optional accent border for mentions (Google Messages style)
    content: @Composable RowScope.() -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorDialogText by remember { mutableStateOf<String?>(null) }
    var showDeletedDialog by remember { mutableStateOf(false) }
    var deletedDialogText by remember { mutableStateOf<String?>(null) }
    var deletedReason by remember { mutableStateOf<String?>(null) }
    
    var bubbleBounds by remember { mutableStateOf(Rect.Zero) }
    var longPressPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var isScrolling by remember { mutableStateOf(false) } // Track if user is scrolling
    var isPressActive by remember { mutableStateOf(false) } // Track if press is still active
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var deletedLoading by remember { mutableStateOf(false) }
    var deletedError by remember { mutableStateOf<String?>(null) }
    var loadedDeletedEvent by remember { mutableStateOf<TimelineEvent?>(null) }
    var loadedDeletedContext by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }

    var showRawJsonDialog by remember { mutableStateOf(false) }
    var rawJsonToShow by remember { mutableStateOf<String?>(null) }

    // Local echoes removed; treat all bubbles as normal
    val isRedacted = event.redactedBy != null
    val isPendingEcho = false
    val isFailedEcho = false
    val deletedBody = event.localContent?.optString("deleted_body")?.takeIf { it.isNotBlank() }
    val deletedFormattedBody = event.localContent?.optString("deleted_formatted_body")?.takeIf { it.isNotBlank() }
    val deletedMsgType = event.localContent?.optString("deleted_msgtype")?.takeIf { it.isNotBlank() }
    val deletedContentJson = event.localContent?.optString("deleted_content_json")?.takeIf { it.isNotBlank() }
    val redactionReason = event.localContent?.optString("redaction_reason")?.takeIf { it.isNotBlank() }
    val deletedContentSummary = remember(event.eventId, deletedBody, deletedFormattedBody, deletedMsgType, deletedContentJson) {
        when {
            deletedFormattedBody != null -> deletedFormattedBody
            deletedBody != null -> deletedBody
            deletedContentJson != null -> {
                val obj = runCatching { org.json.JSONObject(deletedContentJson) }.getOrNull()
                val url = obj?.optString("url")?.takeIf { it.isNotBlank() }
                val fileName = obj
                    ?.optJSONObject("info")
                    ?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?: obj?.optString("body")?.takeIf { it.isNotBlank() }
                val mime = obj
                    ?.optJSONObject("info")
                    ?.optString("mimetype")
                    ?.takeIf { it.isNotBlank() }
                    ?: obj?.optString("mimetype")?.takeIf { it.isNotBlank() }
                val msgTypeLabel = deletedMsgType ?: obj?.optString("msgtype")?.takeIf { it.isNotBlank() }
                listOfNotNull(
                    msgTypeLabel?.let { "Deleted content ($it)" },
                    fileName?.let { "Name: $it" },
                    mime?.let { "MIME: $it" },
                    url?.let { "URL: $it" }
                ).joinToString("\n").ifBlank { "Deleted content (no preview available)" }
            }
            deletedMsgType != null -> "Deleted content (${deletedMsgType})"
            else -> null
        }
    }
    val hasDeletedSnapshot = event.redactedBy != null && (
        deletedBody != null ||
            deletedFormattedBody != null ||
            deletedMsgType != null ||
            deletedContentJson != null
        )

    // Highlight animation when this event is the active scroll target
    val scrollHighlightState = LocalScrollHighlightState.current
    val isHighlightTarget =
        scrollHighlightState.requestId > 0 && scrollHighlightState.eventId == event.eventId
    val highlightAnim = remember(event.eventId) { Animatable(0f) }

    LaunchedEffect(scrollHighlightState.requestId, isHighlightTarget) {
        if (isHighlightTarget) {
            highlightAnim.snapTo(1f)
            highlightAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 5000, easing = FastOutSlowInEasing)
            )
        } else if (!isHighlightTarget && highlightAnim.value > 0f) {
            highlightAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
            )
        }
    }

    val highlightValue = highlightAnim.value
    
    // Watch external trigger and show menu when it changes
    LaunchedEffect(externalMenuTrigger) {
        if (externalMenuTrigger > 0) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            showMenu = true
        }
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    
    // Check if message has been edited (O(1) lookup)
    val hasBeenEdited = remember(event.eventId, appViewModel?.updateCounter) {
        appViewModel?.isMessageEdited(event.eventId) ?: false
    }
    
    // Use powerLevels from parameter, with reactive fallback from appViewModel when null
    // (e.g. in e2ee rooms where room state may load asynchronously)
    val effectivePowerLevels = powerLevels ?: appViewModel?.currentRoomState?.powerLevels
    
    // Calculate power level permissions (works for both e2ee and non-e2ee rooms)
    // 1. Others' messages: can redact when sender's PL < ours AND we have redact permission (my PL >= room redact PL)
    // 2. Our messages: can edit/redact when our PL >= room redact PL
    val myPowerLevel = if (myUserId != null && effectivePowerLevels != null) {
        effectivePowerLevels.users[myUserId] ?: effectivePowerLevels.usersDefault
    } else {
        0
    }
    val senderPowerLevel = if (effectivePowerLevels != null) {
        effectivePowerLevels.users[event.sender] ?: effectivePowerLevels.usersDefault
    } else {
        0
    }
    val redactPowerLevel = effectivePowerLevels?.redact ?: 50
    val canRedactMessage = myPowerLevel >= redactPowerLevel
    
    // Determine which buttons to show (same logic for m.room.message and m.room.encrypted)
    val canEdit = isMine && canRedactMessage // Our messages: require redact level to edit
    val canDelete = if (isMine) {
        // Our messages: require redact permission to delete
        canRedactMessage
    } else {
        // Others' messages: sender's PL must be below ours AND we have redact permission
        senderPowerLevel < myPowerLevel && canRedactMessage
    }
    
    //android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: isMine=$isMine, myPL=$myPowerLevel, senderPL=$senderPowerLevel, redactPL=$redactPowerLevel, canEdit=$canEdit, canDelete=$canDelete")
    
    // Detect dark mode for custom shadow/glow
    val isDarkMode = isSystemInDarkTheme()
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val highlightBorder =
        if (highlightValue > 0.01f) {
            BorderStroke(
                width = (2.dp + 3.dp * highlightValue),
                color = highlightColor.copy(alpha = 0.45f * highlightValue)
            )
        } else {
            null
        }
    
    // Google Messages style: Mention border (accent border for mentions)
    // Mention border takes precedence over highlight border
    val mentionBorderStroke = mentionBorder?.let {
        BorderStroke(
            width = 2.dp,
            color = it
        )
    }
    
    // Use mention border if present, otherwise use highlight border
    val combinedBorder = mentionBorderStroke ?: highlightBorder
    
    // Adjust bubble color based on local echo state
    val bubbleColorAdjusted = when {
        isPendingEcho -> MaterialTheme.colorScheme.tertiaryContainer // warning tone, softer than error
        isFailedEcho -> MaterialTheme.colorScheme.errorContainer
        else -> bubbleColor
    }

    Box {
        Surface(
            modifier = modifier
                .onGloballyPositioned { layoutCoordinates ->
                    // Capture the bubble's position on screen
                    bubbleBounds = layoutCoordinates.boundsInWindow()
                    //android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Bubble bounds: $bubbleBounds")
                }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Regular tap detected")
                        onBubbleClick?.invoke()
                    },
                    onLongClick = {
                        if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Long press detected via combinedClickable")
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        // Use bubble center - we can track coordinates later if needed
                        if (bubbleBounds.width > 0 && bubbleBounds.height > 0) {
                            longPressPosition = androidx.compose.ui.geometry.Offset(
                                bubbleBounds.left + (bubbleBounds.width / 2),
                                bubbleBounds.top + (bubbleBounds.height / 2)
                            )
                        } else {
                            // Fallback if bounds not available yet
                            longPressPosition = androidx.compose.ui.geometry.Offset(0f, 0f)
                        }
                        showMenu = true
                        if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: showMenu set to true, longPressPosition=$longPressPosition")
                    }
                ),
            color = bubbleColorAdjusted,
            shape = bubbleShape,
            tonalElevation = 0.dp,  // No elevation/shadow
            shadowElevation = 0.dp,  // No shadow
            border = combinedBorder
        ) {
            Row(content = content)
        }

        if (showRawJsonDialog) {
            CodeViewer(
                code = rawJsonToShow ?: "",
                onDismiss = {
                    showRawJsonDialog = false
                    rawJsonToShow = null
                }
            )
        }
        
        // Horizontal icon-only menu with fullscreen scrim overlay
        if (showMenu) {
            // Calculate menu width once (outside offset block for reuse)
            val historyButtonEnabled = hasBeenEdited && onShowEditHistory != null
            val viewOriginalButtonEnabled = isRedacted && appViewModel != null
            
            // Code button is always shown, React + Reply are always shown
            val totalButtonCount = 1 + // Code button (always shown)
                2 + // React + Reply (always shown)
                (if (canEdit) 1 else 0) +
                (if (canDelete) 1 else 0) +
                (if (viewOriginalButtonEnabled) 1 else 0) +
                (if (historyButtonEnabled) 1 else 0)
            
            // Calculate actual menu width:
            // - Each button is 40.dp
            // - Spacing between buttons is 4.dp (N-1 spaces for N buttons)
            // - Horizontal padding is 8.dp on each side = 16.dp total
            val buttonSize = 40.dp
            val buttonSpacing = 4.dp
            val horizontalPadding = 16.dp // 8.dp on each side
            val calculatedMenuWidth = buttonSize * totalButtonCount + 
                buttonSpacing * (totalButtonCount - 1) + 
                horizontalPadding
            
            // Calculate effective menu width (clamped to screen bounds)
            val margin = 8.dp
            val maxMenuWidth = with(density) { screenWidth.toDp() - (margin * 2) }
            val effectiveMenuWidth = calculatedMenuWidth.coerceAtMost(maxMenuWidth)
            
            // Use Popup to create a fullscreen overlay independent of parent layout
            Popup(
                onDismissRequest = {
                    if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Popup dismissed")
                    showMenu = false
                },
                properties = PopupProperties(
                    focusable = true, // Makes it dismissible and captures back button
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Fullscreen transparent scrim to capture outside taps
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color.Transparent)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Scrim tapped, dismissing menu")
                                        showMenu = false
                                    }
                                )
                            }
                    )
                    
                    // Card with menu buttons positioned above bubble
                    Card(
                        modifier = Modifier
                            .width(effectiveMenuWidth) // Use exact calculated width (clamped to screen)
                            .offset {
                                with(density) {
                                    val menuWidthPx = effectiveMenuWidth.toPx()
                                    val menuHeight = 50.dp.toPx()
                                    val marginPx = margin.toPx()
                                    
                                    // Use long-press position if available, otherwise fall back to bubble center
                                    val anchorX = longPressPosition?.x 
                                        ?: (bubbleBounds.left + (bubbleBounds.width / 2))
                                    val anchorY = longPressPosition?.y 
                                        ?: bubbleBounds.top
                                    
                                    // Position menu above the long-press point (above user's finger)
                                    // In Android coordinate system: Y=0 at top, increases downward
                                    // To position above: subtract menu height + spacing from anchor Y
                                    val spacingAboveFinger = 64.dp.toPx() // Extra space above finger (large spacing to ensure menu is clearly above finger)
                                    val menuY = anchorY - menuHeight - spacingAboveFinger
                                    
                                    if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Menu Y calculation - anchorY=$anchorY, menuHeight=$menuHeight, spacing=$spacingAboveFinger, finalMenuY=$menuY")
                                    
                                    // Try to center menu on long-press point
                                    var menuX = anchorX - (menuWidthPx / 2)
                                    
                                    // Clamp to keep menu on screen (both left AND right edges)
                                    // First check right edge, then left edge
                                    if (menuX + menuWidthPx > screenWidth - marginPx) {
                                        // Menu would overflow on the right, align to right edge
                                        menuX = screenWidth - menuWidthPx - marginPx
                                    }
                                    if (menuX < marginPx) {
                                        // Menu would overflow on the left, align to left edge
                                        menuX = marginPx
                                    }
                                    
                                    val clampedY = menuY.coerceAtLeast(marginPx)
                                    
                                    if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Menu position: x=$menuX, y=$clampedY, menuWidth=$menuWidthPx, screenWidth=$screenWidth, totalButtonCount=$totalButtonCount, anchorX=$anchorX, longPressPosition=$longPressPosition")
                                    
                                    IntOffset(
                                        x = menuX.toInt(),
                                        y = clampedY.toInt()
                                    )
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            run {
                                // View raw JSON
                                IconButton(
                                    onClick = {
                                        showMenu = false
                                        rawJsonToShow = event.toRawJsonString(2)
                                        showRawJsonDialog = true
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Code,
                                        contentDescription = "View raw JSON",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                // React button
                                IconButton(
                                    onClick = {
                                        if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: React clicked")
                                        showMenu = false
                                        onReact()
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.TagFaces,
                                        contentDescription = "React",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                // Reply button
                                IconButton(
                                    onClick = {
                                        if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Reply clicked")
                                        showMenu = false
                                        onReply()
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Reply,
                                        contentDescription = "Reply",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                // Edit button (only show for our own messages)
                                if (canEdit) {
                                    IconButton(
                                        onClick = {
                                            if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Edit clicked")
                                            showMenu = false
                                            onEdit()
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                // Delete button (only show if we have permission)
                                if (canDelete) {
                                    IconButton(
                                        onClick = {
                                            if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Delete clicked")
                                            showMenu = false
                                            onDelete()
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                
                                // View Original button (only show if message has been deleted/redacted)
                                if (isRedacted && appViewModel != null) {
                                    IconButton(
                                        onClick = {
                                            if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: View Original clicked for event ${event.eventId}")
                                            showMenu = false
                                            deletedDialogText = deletedContentSummary
                                            deletedReason = redactionReason
                                            deletedLoading = true
                                            loadedDeletedEvent = null
                                            loadedDeletedContext = emptyList()
                                            deletedError = null
                                            showDeletedDialog = true
                                            
                                            // Load the original event from cache
                                            // The event itself contains the original content, we just need to clear redactedBy
                                            coroutineScope.launch {
                                                try {
                                                    // Get cached events for the room to find the original event
                                                    val cachedEvents = withContext(Dispatchers.IO) {
                                                        RoomTimelineCache.getCachedEvents(event.roomId)
                                                    }
                                                    
                                                    if (cachedEvents == null || cachedEvents.isEmpty()) {
                                                        if (BuildConfig.DEBUG) android.util.Log.w("ReplyFunctions", "MessageBubbleWithMenu: No cached events found for room ${event.roomId}")
                                                        deletedError = "No cached events available"
                                                        deletedLoading = false
                                                        return@launch
                                                    }
                                                    
                                                    // Find the original event in cache (by event ID)
                                                    // The event we have is the original event, just with redactedBy set
                                                    val originalEvent = cachedEvents.find { it.eventId == event.eventId }
                                                    
                                                    if (originalEvent == null) {
                                                        if (BuildConfig.DEBUG) android.util.Log.w("ReplyFunctions", "MessageBubbleWithMenu: Original event ${event.eventId} not found in cache (${cachedEvents.size} events)")
                                                        deletedError = "Original event not found in cache"
                                                        deletedLoading = false
                                                        return@launch
                                                    }
                                                    
                                                    if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Found original event ${event.eventId} in cache")
                                                    
                                                    // Get context events (events around the original event for better rendering)
                                                    val originalIndex = cachedEvents.indexOf(originalEvent)
                                                    val contextStart = maxOf(0, originalIndex - 2)
                                                    val contextEnd = minOf(cachedEvents.size, originalIndex + 3)
                                                    val contextEvents = cachedEvents.subList(contextStart, contextEnd).toList()
                                                    
                                                    // Create a copy of the original event without redactedBy to show original content
                                                    // The event still contains the original content, redactedBy just marks it as deleted
                                                    val originalEventWithoutRedaction = originalEvent.copy(redactedBy = null)
                                                    
                                                    loadedDeletedEvent = originalEventWithoutRedaction
                                                    loadedDeletedContext = contextEvents
                                                    deletedLoading = false
                                                    
                                                    if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Successfully loaded original event with ${contextEvents.size} context events")
                                                } catch (e: Exception) {
                                                    android.util.Log.e("ReplyFunctions", "MessageBubbleWithMenu: Error loading original event", e)
                                                    deletedError = "Error loading original event: ${e.message}"
                                                    deletedLoading = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Visibility,
                                            contentDescription = "View Original Message",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                
                                // Edit History button (only show if message has been edited)
                                if (hasBeenEdited && onShowEditHistory != null) {
                                    IconButton(
                                        onClick = {
                                            if (BuildConfig.DEBUG) android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Edit History clicked")
                                            showMenu = false
                                            onShowEditHistory()
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.History,
                                            contentDescription = "View Edit History",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showErrorDialog && errorDialogText != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showErrorDialog = false },
                title = { Text("Send failed") },
                text = { Text(errorDialogText ?: "") },
                confirmButton = {
                    TextButton(onClick = { showErrorDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
        if (showDeletedDialog) {
            when {
                deletedLoading -> {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showDeletedDialog = false },
                        title = { Text("Loading original message") },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ExpressiveLoadingIndicator(modifier = Modifier.size(20.dp))
                                Text("Fetching from cache…")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showDeletedDialog = false }) {
                                Text("Close")
                            }
                        }
                    )
                }
                loadedDeletedEvent != null -> {
                    SingleEventRendererDialog(
                        event = loadedDeletedEvent,
                        contextEvents = loadedDeletedContext,
                        appViewModel = appViewModel,
                        homeserverUrl = appViewModel?.homeserverUrl ?: "",
                        authToken = appViewModel?.authToken ?: "",
                        onDismiss = { showDeletedDialog = false },
                        error = deletedError
                    )
                }
                else -> {
                    val fallbackText = deletedError ?: deletedDialogText ?: "Original message not found"
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showDeletedDialog = false },
                        title = { Text("Deleted message") },
                        text = {
                            Column {
                                Text(
                                    text = fallbackText,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                deletedReason?.let { reason ->
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Reason: $reason",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontStyle = FontStyle.Italic
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showDeletedDialog = false }) {
                                Text("Close")
                            }
                        }
                    )
                }
            }
        }
    }
}

