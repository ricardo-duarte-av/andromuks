package net.vrkknn.andromuks.utils

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.TagFaces
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ReplyInfo
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.utils.RedactionUtils
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.supportsHtmlRendering

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
 * @param isMine Whether this reply was sent by the current user (affects styling)
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
    isMine: Boolean,
    modifier: Modifier = Modifier,
    onOriginalMessageClick: () -> Unit = {},
    timelineEvents: List<TimelineEvent> = emptyList(),
    onMatrixUserClick: (String) -> Unit = {}
) {
    // Resolve the event chain to get the latest version of the original message
    val latestOriginalEvent = originalEvent?.let { event ->
        RedactionUtils.resolveEventChain(event.eventId, timelineEvents)
    }
    
    val originalSender = latestOriginalEvent?.sender ?: replyInfo.sender
    val originalBody = latestOriginalEvent?.let { event ->
        // Check if the latest version has been redacted
        if (event.redactedBy != null) {
            // Latest version was deleted - create detailed deletion message using latest redaction
            RedactionUtils.createDeletionMessageForEvent(event, timelineEvents, userProfileCache)
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
                else -> null
            }
        }
    } ?: "Message not found"
    
    val memberProfile = userProfileCache[originalSender]
    val senderName = memberProfile?.displayName ?: originalSender.substringAfterLast(":")
    
    // Outer container for the reply
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // Nested bubble for original message (clickable) - optimized spacing
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOriginalMessageClick() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    // Sender name
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    
                    // Original message content - use HTML if available
                    if (latestOriginalEvent != null && supportsHtmlRendering(latestOriginalEvent)) {
                        HtmlMessageText(
                            event = latestOriginalEvent,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier,
                            onMatrixUserClick = onMatrixUserClick
                        )
                    } else {
                        Text(
                            text = originalBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 0.9
                        )
                    }
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
    
    return this
        .clickable { 
            android.util.Log.d("ReplyFunctions", "MessageBubbleMenu: Click detected, showing menu")
            showMenu = !showMenu 
        }
        .then(
            Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { 
                        android.util.Log.d("ReplyFunctions", "MessageBubbleMenu: Long press detected")
                        showMenu = true 
                    },
                    onDragEnd = { 
                        android.util.Log.d("ReplyFunctions", "MessageBubbleMenu: Drag end")
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
    val profile = userProfileCache[event.sender]
    var displayName = profile?.displayName ?: event.sender
    
    // If we don't have a display name, try to fetch it
    var isFetchingProfile by remember { mutableStateOf(false) }
    
    if (profile?.displayName == null && appViewModel != null && !isFetchingProfile) {
        LaunchedEffect(event.sender) {
            android.util.Log.d("ReplyPreviewInput", "No display name for ${event.sender}, fetching profile...")
            isFetchingProfile = true
            appViewModel.requestUserProfile(event.sender)
            // Note: The profile will be updated via the profile cache when the response comes back
        }
    }
    
    // Get message content - handle both encrypted and non-encrypted messages
    var content = event.content ?: event.decrypted
    var body = content?.optString("body", "") ?: ""
    var msgType = content?.optString("msgtype", "") ?: ""
    
    // If content is null OR we're missing important fields (body, msgType), fetch the full event
    var isFetchingEvent by remember { mutableStateOf(false) }
    var fetchedEvent by remember { mutableStateOf<TimelineEvent?>(null) }
    
    val needsFullEvent = content == null || body.isBlank() || msgType.isBlank()
    
    if (needsFullEvent && appViewModel != null && roomId != null && !isFetchingEvent && fetchedEvent == null) {
        LaunchedEffect(event.eventId) {
            android.util.Log.d("ReplyPreviewInput", "Missing content/body/msgType, fetching full event details for: ${event.eventId}")
            android.util.Log.d("ReplyPreviewInput", "Content null: ${content == null}, Body blank: ${body.isBlank()}, MsgType blank: ${msgType.isBlank()}")
            isFetchingEvent = true
            appViewModel.getEvent(roomId, event.eventId) { fullEvent ->
                isFetchingEvent = false
                if (fullEvent != null) {
                    android.util.Log.d("ReplyPreviewInput", "Successfully fetched event: ${fullEvent.eventId}")
                    fetchedEvent = fullEvent
                } else {
                    android.util.Log.w("ReplyPreviewInput", "Failed to fetch event: ${event.eventId}")
                }
            }
        }
    }
    
    // Use fetched event data if available
    if (fetchedEvent != null) {
        android.util.Log.d("ReplyPreviewInput", "Processing fetched event data...")
        android.util.Log.d("ReplyPreviewInput", "Fetched event type: '${fetchedEvent!!.type}'")
        
        // Choose content source based on event type
        content = when (fetchedEvent!!.type) {
            "m.room.encrypted" -> {
                android.util.Log.d("ReplyPreviewInput", "Event is encrypted, using decrypted content")
                fetchedEvent!!.decrypted
            }
            "m.room.message" -> {
                android.util.Log.d("ReplyPreviewInput", "Event is message, using content")
                fetchedEvent!!.content
            }
            else -> {
                android.util.Log.d("ReplyPreviewInput", "Unknown event type, trying decrypted then content")
                fetchedEvent!!.decrypted ?: fetchedEvent!!.content
            }
        }
        
        android.util.Log.d("ReplyPreviewInput", "Selected content: $content")
        
        body = content?.optString("body", "") ?: ""
        msgType = content?.optString("msgtype", "") ?: ""
        
        android.util.Log.d("ReplyPreviewInput", "Fetched body: '$body'")
        android.util.Log.d("ReplyPreviewInput", "Fetched msgType: '$msgType'")
        
        // Update display name if we got a full event with sender info
        if (fetchedEvent!!.sender.isNotBlank()) {
            val fetchedProfile = userProfileCache[fetchedEvent!!.sender]
            if (fetchedProfile?.displayName != null) {
                // The display name will be updated in the UI automatically
            }
        }
    }
    
    // Final debug logging
    android.util.Log.d("ReplyPreviewInput", "=== FINAL VALUES ===")
    android.util.Log.d("ReplyPreviewInput", "Event sender: ${event.sender}")
    android.util.Log.d("ReplyPreviewInput", "Profile: $profile")
    android.util.Log.d("ReplyPreviewInput", "Display name: $displayName")
    android.util.Log.d("ReplyPreviewInput", "Final body: '$body'")
    android.util.Log.d("ReplyPreviewInput", "Final msgType: '$msgType'")
    android.util.Log.d("ReplyPreviewInput", "Content null: ${content == null}")
    android.util.Log.d("ReplyPreviewInput", "Fetched event null: ${fetchedEvent == null}")
    android.util.Log.d("ReplyPreviewInput", "Is fetching event: $isFetchingEvent")
    android.util.Log.d("ReplyPreviewInput", "Is fetching profile: $isFetchingProfile")
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .padding(bottom = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reply icon
            Icon(
                imageVector = Icons.Filled.Reply,
                contentDescription = "Replying to",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            
            // Message preview
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        isFetchingEvent -> "Loading message..."
                        msgType == "m.image" -> "📷 Image"
                        msgType == "m.video" -> "🎥 Video"
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
        tonalElevation = 0.dp
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBubbleWithMenu(
    event: TimelineEvent,
    bubbleColor: androidx.compose.ui.graphics.Color,
    bubbleShape: androidx.compose.foundation.shape.RoundedCornerShape,
    modifier: Modifier = Modifier,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    
    Box {
        Surface(
            modifier = modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { 
                            android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Long press detected")
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            showMenu = true 
                        },
                        onTap = {
                            android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Regular tap detected")
                            // Don't do anything on regular tap - let other click handlers work
                        }
                    )
                },
            color = bubbleColor,
            shape = bubbleShape,
            tonalElevation = 2.dp
        ) {
            Row(content = content)
        }
        
        // Horizontal icon-only menu
        if (showMenu) {
            // Full-screen transparent overlay to capture outside taps
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Outside tap detected, dismissing menu")
                                showMenu = false
                            }
                        )
                    }
            )
            
            Card(
                modifier = Modifier
                    .padding(8.dp)
                    .background(androidx.compose.ui.graphics.Color.Transparent)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Consume taps on the menu itself so they don't dismiss it
                                android.util.Log.d("ReplyFunctions", "MessageBubbleWithMenu: Menu tap consumed")
                            }
                        )
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
                    // React button
                    IconButton(
                        onClick = {
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
                            showMenu = false
                            onReply()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Reply,
                            contentDescription = "Reply",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    // Edit button
                    IconButton(
                        onClick = {
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
                    
                    // Delete button
                    IconButton(
                        onClick = {
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
            }
        }
    }
}

