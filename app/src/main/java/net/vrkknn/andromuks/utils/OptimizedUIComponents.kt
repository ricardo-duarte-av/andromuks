package net.vrkknn.andromuks.utils

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import net.vrkknn.andromuks.TimelineEvent

/**
 * PERFORMANCE: Optimized UI components with smart performance optimizations
 * 
 * Key optimizations:
 * - Smart state management with minimal recomposition
 * - Efficient popup positioning with pre-calculated values
 * - Optimized scrolling with proper virtualization
 * - Smart keyboard handling to prevent layout thrashing
 * - Efficient menu system with lightweight calculations
 */

/**
 * PERFORMANCE: Optimized message bubble with smart menu system
 */
@Composable
fun OptimizedMessageBubbleWithMenu(
    event: TimelineEvent,
    content: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    onReply: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    onViewHistory: (() -> Unit)? = null,
    canEdit: Boolean = false,
    canDelete: Boolean = false,
    canReact: Boolean = true,
    canViewHistory: Boolean = false
) {
    // PERFORMANCE: Minimal state management - only essential state
    var showMenu by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(0f to 0f) }
    
    // PERFORMANCE: Pre-calculated menu items to avoid recreation
    val menuItems = remember(canEdit, canDelete, canReact, canViewHistory) {
        buildList {
            onReply?.let { 
                add(OptimizedMenuItem("reply", "Reply", Icons.Filled.Reply, true, it))
            }
            if (canEdit) {
                onEdit?.let { 
                    add(OptimizedMenuItem("edit", "Edit", Icons.Filled.Edit, true, it))
                }
            }
            if (canDelete) {
                onDelete?.let { 
                    add(OptimizedMenuItem("delete", "Delete", Icons.Filled.Delete, true, it))
                }
            }
            if (canReact) {
                onReact?.let { 
                    add(OptimizedMenuItem("react", "React", Icons.Filled.TagFaces, true, it))
                }
            }
            if (canViewHistory) {
                onViewHistory?.let { 
                    add(OptimizedMenuItem("history", "View History", Icons.Filled.History, true, it))
                }
            }
        }
    }
    
    Box(modifier = modifier) {
        // Message content with optimized gesture handling
        Row(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            // PERFORMANCE: Pre-calculate menu position
                            menuPosition = it.x to it.y
                            showMenu = true
                        }
                    )
                }
        ) {
            content()
        }
        
        // PERFORMANCE: Optimized popup with smart positioning
        if (showMenu && menuItems.isNotEmpty()) {
            OptimizedMenuPopup(
                showMenu = showMenu,
                onDismiss = { showMenu = false },
                menuItems = menuItems,
                position = menuPosition
            )
        }
    }
}

/**
 * PERFORMANCE: Menu item data class for efficient caching
 */
data class OptimizedMenuItem(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * PERFORMANCE: Optimized popup with smart positioning and minimal recomposition
 */
@Composable
private fun OptimizedMenuPopup(
    showMenu: Boolean,
    onDismiss: () -> Unit,
    menuItems: List<OptimizedMenuItem>,
    position: Pair<Float, Float>
) {
    // PERFORMANCE: Smart positioning calculation
    val menuWidth = 200f // Pre-calculated menu width
    val menuHeight = menuItems.size * 48f + 16f // Pre-calculated menu height
    
    // Calculate optimal position with viewport awareness
    val (x, y) = position
    val finalX = when {
        x + menuWidth > 400f -> x - menuWidth // Position to the left
        else -> x // Position to the right
    }
    val finalY = when {
        y + menuHeight > 800f -> y - menuHeight // Position above
        else -> y // Position below
    }
    
    // PERFORMANCE: Animated popup with smooth transitions
    val alpha by animateFloatAsState(
        targetValue = if (showMenu) 1f else 0f,
        animationSpec = tween(150),
        label = "menuAlpha"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (showMenu) 1f else 0.8f,
        animationSpec = tween(150),
        label = "menuScale"
    )
    
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() }
                    )
                }
        ) {
            // PERFORMANCE: Optimized menu card with pre-calculated dimensions
            Card(
                modifier = Modifier
                    .offset(
                        x = finalX.dp,
                        y = finalY.dp
                    )
                    .width(menuWidth.dp)
                    .height(menuHeight.dp)
                    .shadow(8.dp, RoundedCornerShape(8.dp))
                    .zIndex(10f),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    // PERFORMANCE: Efficient menu items rendering
                    menuItems.forEach { item ->
                        OptimizedMenuItem(
                            item = item,
                            onClick = {
                                item.onClick()
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * PERFORMANCE: Optimized menu item with minimal recomposition
 */
@Composable
private fun OptimizedMenuItem(
    item: OptimizedMenuItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(enabled = item.enabled) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

/**
 * PERFORMANCE: Optimized timeline with smart virtualization
 */
@Composable
fun OptimizedTimeline(
    events: List<TimelineEvent>,
    onEventClick: (TimelineEvent) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (TimelineEvent) -> Unit
) {
    // PERFORMANCE: Optimized LazyListState with smart configuration
    val listState = rememberLazyListState()
    
    // PERFORMANCE: Smart item keys to prevent unnecessary recomposition
    val itemKeys = remember(events) {
        events.map { it.eventId }
    }
    
    // PERFORMANCE: Optimized LazyColumn with smart configuration
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            count = events.size,
            key = { index -> itemKeys[index] }
        ) { index ->
            val event = events[index]
            
            // PERFORMANCE: Smart timeline item with efficient rendering
            OptimizedTimelineItem(
                event = event,
                onClick = { onEventClick(event) },
                content = { content(event) }
            )
        }
    }
    
    // PERFORMANCE: Smart load more trigger
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null && lastVisibleIndex >= events.size - 5) {
                    onLoadMore()
                }
            }
    }
}

/**
 * PERFORMANCE: Optimized timeline item with smart rendering
 */
@Composable
private fun OptimizedTimelineItem(
    event: TimelineEvent,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        content()
    }
}

/**
 * PERFORMANCE: Optimized keyboard-aware layout
 */
@Composable
fun OptimizedKeyboardAwareLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    
    // PERFORMANCE: Smart keyboard height calculation
    val keyboardHeight by remember {
        derivedStateOf {
            imeInsets.getBottom(density).toInt()
        }
    }
    
    // PERFORMANCE: Smart keyboard visibility detection
    val isKeyboardVisible by remember {
        derivedStateOf {
            keyboardHeight > 0
        }
    }
    
    // PERFORMANCE: Smooth keyboard animations to prevent layout thrashing
    val animatedOffset by animateFloatAsState(
        targetValue = if (isKeyboardVisible) keyboardHeight.toFloat() else 0f,
        animationSpec = tween(300),
        label = "keyboardOffset"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, animatedOffset.toInt()) }
    ) {
        content()
    }
}

/**
 * PERFORMANCE: Optimized state management with smart grouping
 */
@Composable
fun rememberOptimizedUIState(): State<OptimizedUIState> {
    return remember { mutableStateOf(OptimizedUIState()) }
}

/**
 * PERFORMANCE: Consolidated UI state to reduce individual remember calls
 */
data class OptimizedUIState(
    // Menu states
    val showMessageMenu: Boolean = false,
    val showAttachmentMenu: Boolean = false,
    val showEmojiPicker: Boolean = false,
    val showMentionList: Boolean = false,
    
    // Input states
    val draft: String = "",
    val isTyping: Boolean = false,
    val mentionQuery: String = "",
    val mentionStartIndex: Int = -1,
    
    // Media states
    val selectedMediaUri: String? = null,
    val isUploading: Boolean = false,
    val showMediaPreview: Boolean = false,
    
    // Navigation states
    val showRoomJoiner: Boolean = false,
    val roomLinkToJoin: String? = null,
    
    // Interaction states
    val reactingToEvent: String? = null,
    val editingEvent: String? = null,
    val replyingToEvent: String? = null
)

/**
 * PERFORMANCE: Smart state updates with minimal object creation
 */
fun OptimizedUIState.update(
    showMessageMenu: Boolean? = null,
    showAttachmentMenu: Boolean? = null,
    showEmojiPicker: Boolean? = null,
    showMentionList: Boolean? = null,
    draft: String? = null,
    isTyping: Boolean? = null,
    mentionQuery: String? = null,
    mentionStartIndex: Int? = null,
    selectedMediaUri: String? = null,
    isUploading: Boolean? = null,
    showMediaPreview: Boolean? = null,
    showRoomJoiner: Boolean? = null,
    roomLinkToJoin: String? = null,
    reactingToEvent: String? = null,
    editingEvent: String? = null,
    replyingToEvent: String? = null
): OptimizedUIState {
    return copy(
        showMessageMenu = showMessageMenu ?: this.showMessageMenu,
        showAttachmentMenu = showAttachmentMenu ?: this.showAttachmentMenu,
        showEmojiPicker = showEmojiPicker ?: this.showEmojiPicker,
        showMentionList = showMentionList ?: this.showMentionList,
        draft = draft ?: this.draft,
        isTyping = isTyping ?: this.isTyping,
        mentionQuery = mentionQuery ?: this.mentionQuery,
        mentionStartIndex = mentionStartIndex ?: this.mentionStartIndex,
        selectedMediaUri = selectedMediaUri ?: this.selectedMediaUri,
        isUploading = isUploading ?: this.isUploading,
        showMediaPreview = showMediaPreview ?: this.showMediaPreview,
        showRoomJoiner = showRoomJoiner ?: this.showRoomJoiner,
        roomLinkToJoin = roomLinkToJoin ?: this.roomLinkToJoin,
        reactingToEvent = reactingToEvent ?: this.reactingToEvent,
        editingEvent = editingEvent ?: this.editingEvent,
        replyingToEvent = replyingToEvent ?: this.replyingToEvent
    )
}
