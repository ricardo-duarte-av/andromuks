package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.SingleEventRendererDialog
import net.vrkknn.andromuks.utils.CodeViewer
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TagFaces
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Visibility
import kotlin.math.max
import kotlin.math.min

/**
 * Data class to hold message menu configuration
 */
data class MessageMenuConfig(
    val event: TimelineEvent,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canViewOriginal: Boolean,
    val canViewEditHistory: Boolean,
    val onReply: () -> Unit,
    val onReact: () -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val onShowEditHistory: (() -> Unit)?,
    val appViewModel: net.vrkknn.andromuks.AppViewModel?
)

/**
 * Bottom menu bar for message actions (similar to attachment menu)
 * Slides from bottom above the typing section
 */
@Composable
fun MessageMenuBar(
    menuConfig: MessageMenuConfig?,
    onDismiss: () -> Unit,
    buttonsAlpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    if (menuConfig == null) return
    
    val event = menuConfig.event
    val coroutineScope = rememberCoroutineScope()
    var showRawJsonDialog by remember { mutableStateOf(false) }
    var rawJsonToShow by remember { mutableStateOf<String?>(null) }
    var showDeletedDialog by remember { mutableStateOf(false) }
    var deletedDialogText by remember { mutableStateOf<String?>(null) }
    var deletedReason by remember { mutableStateOf<String?>(null) }
    var deletedLoading by remember { mutableStateOf(false) }
    var deletedError by remember { mutableStateOf<String?>(null) }
    var loadedDeletedEvent by remember { mutableStateOf<TimelineEvent?>(null) }
    var loadedDeletedContext by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    
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
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(5f)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Code button (always shown)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.size(56.dp).alpha(buttonsAlpha)
                    ) {
                        IconButton(
                            onClick = {
                                rawJsonToShow = event.toRawJsonString(2)
                                showRawJsonDialog = true
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Code,
                                contentDescription = "View raw JSON",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // React button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.size(56.dp).alpha(buttonsAlpha)
                    ) {
                        IconButton(
                            onClick = {
                                onDismiss()
                                menuConfig.onReact()
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.TagFaces,
                                contentDescription = "React",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "React",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Reply button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.size(56.dp).alpha(buttonsAlpha)
                    ) {
                        IconButton(
                            onClick = {
                                onDismiss()
                                menuConfig.onReply()
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = "Reply",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Reply",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Edit button (always shown, disabled if not available)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.size(56.dp).alpha(buttonsAlpha)
                    ) {
                        IconButton(
                            onClick = {
                                if (menuConfig.canEdit) {
                                    onDismiss()
                                    menuConfig.onEdit()
                                }
                            },
                            enabled = menuConfig.canEdit,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit",
                                tint = if (menuConfig.canEdit) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Edit",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (menuConfig.canEdit) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
                
                // Delete button (always shown, disabled if not available)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.size(56.dp).alpha(buttonsAlpha)
                    ) {
                        IconButton(
                            onClick = {
                                if (menuConfig.canDelete) {
                                    onDismiss()
                                    menuConfig.onDelete()
                                }
                            },
                            enabled = menuConfig.canDelete,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = if (menuConfig.canDelete) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (menuConfig.canDelete) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
                
                // View Original button (always shown, disabled if not available)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.size(56.dp).alpha(buttonsAlpha)
                    ) {
                        IconButton(
                            onClick = {
                                if (menuConfig.canViewOriginal && menuConfig.appViewModel != null) {
                                    deletedDialogText = deletedContentSummary
                                    deletedReason = redactionReason
                                    deletedLoading = true
                                    loadedDeletedEvent = null
                                    loadedDeletedContext = emptyList()
                                    deletedError = null
                                    showDeletedDialog = true
                                    
                                    // Load the original event from cache
                                    coroutineScope.launch {
                                        try {
                                            val cachedEvents = withContext(Dispatchers.IO) {
                                                RoomTimelineCache.getCachedEvents(event.roomId)
                                            }
                                            
                                            if (cachedEvents == null || cachedEvents.isEmpty()) {
                                                if (BuildConfig.DEBUG) android.util.Log.w("MessageMenuBar", "No cached events found for room ${event.roomId}")
                                                deletedError = "No cached events available"
                                                deletedLoading = false
                                                return@launch
                                            }
                                            
                                            val originalEvent = cachedEvents.find { it.eventId == event.eventId }
                                            
                                            if (originalEvent == null) {
                                                if (BuildConfig.DEBUG) android.util.Log.w("MessageMenuBar", "Original event ${event.eventId} not found in cache")
                                                deletedError = "Original event not found in cache"
                                                deletedLoading = false
                                                return@launch
                                            }
                                            
                                            val originalIndex = cachedEvents.indexOf(originalEvent)
                                            val contextStart = max(0, originalIndex - 2)
                                            val contextEnd = min(cachedEvents.size, originalIndex + 3)
                                            val contextEvents = cachedEvents.subList(contextStart, contextEnd).toList()
                                            
                                            val originalEventWithoutRedaction = originalEvent.copy(redactedBy = null)
                                            
                                            loadedDeletedEvent = originalEventWithoutRedaction
                                            loadedDeletedContext = contextEvents
                                            deletedLoading = false
                                        } catch (e: Exception) {
                                            android.util.Log.e("MessageMenuBar", "Error loading original event", e)
                                            deletedError = "Error loading original event: ${e.message}"
                                            deletedLoading = false
                                        }
                                    }
                                }
                            },
                            enabled = menuConfig.canViewOriginal && menuConfig.appViewModel != null,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Visibility,
                                contentDescription = "View Original Message",
                                tint = if (menuConfig.canViewOriginal && menuConfig.appViewModel != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Original",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (menuConfig.canViewOriginal && menuConfig.appViewModel != null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
                
                // Edit History button (always shown, disabled if not available)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.size(56.dp).alpha(buttonsAlpha)
                    ) {
                        IconButton(
                            onClick = {
                                if (menuConfig.canViewEditHistory && menuConfig.onShowEditHistory != null) {
                                    onDismiss()
                                    menuConfig.onShowEditHistory?.invoke()
                                }
                            },
                            enabled = menuConfig.canViewEditHistory && menuConfig.onShowEditHistory != null,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = "View Edit History",
                                tint = if (menuConfig.canViewEditHistory && menuConfig.onShowEditHistory != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "History",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (menuConfig.canViewEditHistory && menuConfig.onShowEditHistory != null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
            }
        }
    }
    
    // Dialogs
    if (showRawJsonDialog) {
        CodeViewer(
            code = rawJsonToShow ?: "",
            onDismiss = {
                showRawJsonDialog = false
                rawJsonToShow = null
            }
        )
    }
    
    if (showDeletedDialog) {
        when {
            deletedLoading -> {
                AlertDialog(
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
                    appViewModel = menuConfig.appViewModel,
                    homeserverUrl = menuConfig.appViewModel?.homeserverUrl ?: "",
                    authToken = menuConfig.appViewModel?.authToken ?: "",
                    onDismiss = { showDeletedDialog = false },
                    error = deletedError
                )
            }
            else -> {
                val fallbackText = deletedError ?: deletedDialogText ?: "Original message not found"
                AlertDialog(
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

