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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.filled.PushPin
import kotlin.math.max
import kotlin.math.min

/** No-op fling: scroll stops when user releases, so we can snap without fighting a decay animation. */
private val NoFlingBehavior = object : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float = 0f
}

/**
 * Data class to hold message menu configuration
 */
data class MessageMenuConfig(
    val event: TimelineEvent,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canViewOriginal: Boolean,
    val canViewEditHistory: Boolean,
    val canPin: Boolean,
    val isPinned: Boolean,
    val onReply: () -> Unit,
    val onReact: () -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val onPin: () -> Unit,
    val onUnpin: () -> Unit,
    val onShowEditHistory: (() -> Unit)?,
    val appViewModel: net.vrkknn.andromuks.AppViewModel?,
    val onViewSource: ((String) -> Unit)? = null
)

/**
 * Sealed class for menu button items
 */
sealed class MenuButtonItem {
    data class Button(
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val label: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit
    ) : MenuButtonItem()
    
    object Empty : MenuButtonItem()
}

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
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val buttonSpacing = 8.dp
                // Calculate button width to fit exactly 7 buttons in the available width
                // No horizontal padding - buttons fill the full width
                val availableWidthPx = with(density) { maxWidth.toPx() }
                val spacingPx = with(density) { (buttonSpacing * 6).toPx() } // 6 gaps between 7 buttons
                val buttonWidthPx = (availableWidthPx - spacingPx) / 7
                val buttonWidth = with(density) { buttonWidthPx.toDp() }
                val pageWidthPx = buttonWidthPx * 7 + spacingPx // Total width of 7 buttons
            
            val page1Buttons = listOf(
                MenuButtonItem.Button(
                    icon = Icons.Filled.TagFaces,
                    label = "React",
                    onClick = {
                        onDismiss()
                        menuConfig.onReact()
                    }
                ),
                MenuButtonItem.Button(
                    icon = Icons.AutoMirrored.Filled.Reply,
                    label = "Reply",
                    onClick = {
                        onDismiss()
                        menuConfig.onReply()
                    }
                ),
                MenuButtonItem.Button(
                    icon = Icons.Filled.Edit,
                    label = "Edit",
                    enabled = menuConfig.canEdit,
                    onClick = {
                        if (menuConfig.canEdit) {
                            onDismiss()
                            menuConfig.onEdit()
                        }
                    }
                ),
                MenuButtonItem.Button(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    enabled = menuConfig.canDelete,
                    onClick = {
                        if (menuConfig.canDelete) {
                            onDismiss()
                            menuConfig.onDelete()
                        }
                    }
                ),
                MenuButtonItem.Button(
                    icon = Icons.Filled.Visibility,
                    label = "Original",
                    enabled = menuConfig.canViewOriginal && menuConfig.appViewModel != null,
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
                    }
                ),
                MenuButtonItem.Button(
                    icon = Icons.Filled.History,
                    label = "History",
                    enabled = menuConfig.canViewEditHistory && menuConfig.onShowEditHistory != null,
                    onClick = {
                        if (menuConfig.canViewEditHistory && menuConfig.onShowEditHistory != null) {
                            onDismiss()
                            menuConfig.onShowEditHistory?.invoke()
                        }
                    }
                ),
                MenuButtonItem.Button(
                    icon = Icons.Filled.PushPin,
                    label = if (menuConfig.isPinned) "Unpin" else "Pin",
                    enabled = menuConfig.canPin,
                    onClick = {
                        if (menuConfig.canPin) {
                            onDismiss()
                            if (menuConfig.isPinned) {
                                menuConfig.onUnpin()
                            } else {
                                menuConfig.onPin()
                            }
                        }
                    }
                )
            )
            
            val page2Buttons = listOf(
                MenuButtonItem.Button(
                    icon = Icons.Filled.Code,
                    label = "Source",
                    onClick = {
                        onDismiss()
                        val json = event.toRawJsonString(2)
                        if (menuConfig.onViewSource != null) {
                            menuConfig.onViewSource!!.invoke(json)
                        } else {
                            rawJsonToShow = json
                            showRawJsonDialog = true
                        }
                    }
                )
            ) + List(6) { MenuButtonItem.Empty }
            
            val allButtons = page1Buttons + page2Buttons
            
            // Use scrollable Row instead of LazyRow so buttons on the second page receive taps
            // (LazyRow is known to consume taps after scrolling.)
            val scrollState = rememberScrollState()
            val contentWidthPx = 14 * buttonWidthPx + 13 * spacingPx
            val contentWidthDp = with(density) { contentWidthPx.toDp() }
            var lastScrollPosition by remember { mutableStateOf(0f) }
            var lastScrollTime by remember { mutableStateOf(0L) }
            var scrollVelocity by remember { mutableStateOf(0f) }
            var snapInProgress by remember { mutableStateOf(false) }
            
            LaunchedEffect(scrollState.value) {
                kotlinx.coroutines.delay(150)
                if (snapInProgress) return@LaunchedEffect
                val currentTime = System.currentTimeMillis()
                val currentPosition = scrollState.value.toFloat()
                if (lastScrollTime > 0 && currentTime > lastScrollTime) {
                    val deltaTime = (currentTime - lastScrollTime).coerceAtLeast(1)
                    val deltaPosition = currentPosition - lastScrollPosition
                    scrollVelocity = (deltaPosition / deltaTime) * 1000f
                }
                lastScrollPosition = currentPosition
                lastScrollTime = currentTime
                val targetOffset = when {
                    scrollVelocity < -800f && currentPosition < pageWidthPx -> pageWidthPx.toInt().coerceAtMost(scrollState.maxValue)
                    scrollVelocity > 800f && currentPosition >= pageWidthPx -> 0
                    currentPosition < pageWidthPx / 2 -> 0
                    else -> pageWidthPx.toInt().coerceAtMost(scrollState.maxValue)
                }
                if (kotlin.math.abs(scrollState.value - targetOffset) > 5) {
                    snapInProgress = true
                    try {
                        scrollState.scrollTo(targetOffset)
                    } finally {
                        snapInProgress = false
                    }
                }
                scrollVelocity = 0f
                lastScrollTime = 0L
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .width(contentWidthDp)
                    .horizontalScroll(scrollState, flingBehavior = NoFlingBehavior),
                horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
            ) {
                allButtons.forEach { buttonItem ->
                    when (buttonItem) {
                        is MenuButtonItem.Button -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(buttonWidth)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.size(56.dp).alpha(buttonsAlpha)
                                ) {
                                    IconButton(
                                        onClick = buttonItem.onClick,
                                        enabled = buttonItem.enabled,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = buttonItem.icon,
                                            contentDescription = buttonItem.label,
                                            tint = if (buttonItem.enabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = buttonItem.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (buttonItem.enabled) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    }
                                )
                            }
                        }
                        is MenuButtonItem.Empty -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(buttonWidth)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.size(56.dp).alpha(0.1f)
                                ) {
                                    // Empty button - placeholder for future use
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0f)
                                )
                            }
                        }
                    }
                }
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

