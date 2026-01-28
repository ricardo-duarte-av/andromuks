package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import net.vrkknn.andromuks.TimelineEvent


import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import net.vrkknn.andromuks.utils.AdvancedExoPlayerManager
import net.vrkknn.andromuks.utils.ProgressiveImageLoader
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.DownloadDeduplicationManager
import net.vrkknn.andromuks.utils.BubblePalette
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.os.Build
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.ImageLoader
import coil.size.Size
import coil.size.Precision
import java.io.File
import java.security.MessageDigest
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import coil.request.CachePolicy
import coil.request.ImageRequest
import net.vrkknn.andromuks.MediaMessage
import net.vrkknn.andromuks.utils.BlurHashUtils
import net.vrkknn.andromuks.utils.MediaUtils
import net.vrkknn.andromuks.AppViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.DisposableEffect
import android.app.DownloadManager
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileOutputStream

/**
 * DEPRECATED: MediaCache has been replaced by IntelligentMediaCache.
 * All functionality has been migrated to IntelligentMediaCache which provides:
 * - Viewport-aware caching
 * - Smart eviction based on usage and visibility
 * - Access count tracking
 * - Better performance and memory management
 * 
 * This object is kept for backward compatibility but should not be used in new code.
 * Use IntelligentMediaCache instead.
 */
@Deprecated("Use IntelligentMediaCache instead", ReplaceWith("IntelligentMediaCache"))
object MediaCache {
    // All methods delegate to IntelligentMediaCache for backward compatibility
    // This allows old code to continue working while we migrate
    
    @Deprecated("Use IntelligentMediaCache.getCacheDir() instead")
    fun getCacheDir(context: android.content.Context): File {
        return IntelligentMediaCache.getCacheDir(context)
    }
    
    @Deprecated("Use IntelligentMediaCache.getCacheKey() instead")
    fun getCacheKey(mxcUrl: String): String {
        return IntelligentMediaCache.getCacheKey(mxcUrl)
    }
    
    @Deprecated("Use IntelligentMediaCache.getCachedFile() instead")
    fun getCachedFile(context: android.content.Context, mxcUrl: String): File? {
        return kotlinx.coroutines.runBlocking {
            IntelligentMediaCache.getCachedFile(context, mxcUrl)
        }
    }
    
    @Deprecated("Use IntelligentMediaCache.isCached() instead")
    fun isCached(context: android.content.Context, mxcUrl: String): Boolean {
        return IntelligentMediaCache.isCached(context, mxcUrl)
    }
    
    @Deprecated("Use IntelligentMediaCache.downloadAndCache() instead")
    suspend fun downloadAndCache(
        context: android.content.Context,
        mxcUrl: String,
        httpUrl: String,
        authToken: String
    ): File? {
        return IntelligentMediaCache.downloadAndCache(context, mxcUrl, httpUrl, authToken)
    }
    
    @Deprecated("Use IntelligentMediaCache.cleanupCache() instead")
    suspend fun cleanupCache(context: android.content.Context) {
        IntelligentMediaCache.cleanupCache(context)
    }
}

/**
 * Format timestamp for media messages
 */
private fun formatMediaTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(date)
}

/**
 * Format video duration from milliseconds to MM:SS or HH:MM:SS format
 */
private fun formatDuration(durationMs: Int): String {
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}

/**
 * Renders a caption with HTML support when available.
 * If caption is empty/null, displays the filename instead.
 */
@Composable
private fun MediaCaption(
    caption: String?,
    filename: String,
    event: TimelineEvent?,
    homeserverUrl: String,
    authToken: String,
    onUserClick: (String) -> Unit = {},
    isCompactMedia: Boolean = false, // For audio and file messages
    appViewModel: AppViewModel? = null
) {
    // Always show something: caption if available, otherwise filename
    val displayText = if (!caption.isNullOrBlank()) caption else filename
    
    // Check if the event supports HTML rendering (has sanitized_html or formatted_body)
    // Only use HTML if we're showing the caption (not filename fallback)
    val supportsHtml = !caption.isNullOrBlank() && event != null && supportsHtmlRendering(event)
    
    // Use minimal padding for compact media messages (audio and file)
    val padding = if (isCompactMedia) {
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    } else {
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    }
    
    if (supportsHtml) {
        // Use HTML rendering for caption
        HtmlMessageText(
            event = event,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = padding,
            onMatrixUserClick = onUserClick,
            appViewModel = appViewModel
        )
    } else {
        // Fallback to plain text (caption or filename)
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = padding
        )
    }
}

/**
 * Displays timestamp inside media bubble (for consecutive messages)
 */
@Composable
private fun MediaBubbleTimestamp(
    timestamp: Long,
    editedBy: TimelineEvent?,
    isMine: Boolean,
    isConsecutive: Boolean,
    onEditedClick: (() -> Unit)? = null
) {
    if (isConsecutive) {
        val text = if (editedBy != null) {
            "${formatMediaTimestamp(timestamp)} (edited)"
        } else {
            formatMediaTimestamp(timestamp)
        }
        val baseModifier = Modifier
            .wrapContentWidth(if (isMine) Alignment.Start else Alignment.End)
            .padding(horizontal = 12.dp, vertical = 4.dp)
        val clickableModifier = if (editedBy != null && onEditedClick != null) {
            baseModifier.clickable { onEditedClick() }
        } else {
            baseModifier
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = clickableModifier
        )
    }
}

/**
 * Displays a media message (image or video) in a Material3 bubble with proper aspect ratio and styling.
 * 
 * This function renders media content with BlurHash placeholders for images, proper aspect ratio
 * constraints, and fallback handling for videos. It supports both encrypted and unencrypted media
 * with appropriate URL parameters and authentication headers.
 * 
 * @param mediaMessage MediaMessage object containing URL, filename, media info, and caption
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing media
 * @param isMine Whether this message was sent by the current user (affects bubble styling)
 * @param isEncrypted Whether this is encrypted media (adds ?encrypted=true to URL)
 * @param modifier Modifier to apply to the media message container
 */
@Composable
fun MediaMessage(
    mediaMessage: MediaMessage,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    isEncrypted: Boolean = false,
    modifier: Modifier = Modifier,
    event: TimelineEvent? = null,
    timestamp: Long? = null,
    isConsecutive: Boolean = false,
    editedBy: TimelineEvent? = null,
    onReply: () -> Unit = {},
    onReact: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    myUserId: String? = null,
    powerLevels: net.vrkknn.andromuks.PowerLevelsInfo? = null,
    appViewModel: net.vrkknn.andromuks.AppViewModel? = null,
    onBubbleClick: (() -> Unit)? = null,
    onShowEditHistory: (() -> Unit)? = null,
    bubbleColorOverride: Color? = null,
    hasBeenEditedOverride: Boolean? = null
) {
    val useThumbnails = appViewModel?.loadThumbnailsIfAvailable ?: true
    var showImageViewer by remember { mutableStateOf(false) }
    var showVideoPlayer by remember { mutableStateOf(false) }
    // Shared state to trigger menu from image long press
    var triggerMenuFromImage by remember { mutableStateOf(0) }
    
    // Show image viewer dialog when image is tapped
    if (showImageViewer && mediaMessage.msgType == "m.image") {
        ImageViewerDialog(
            mediaMessage = mediaMessage,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            isEncrypted = isEncrypted,
            onDismiss = { showImageViewer = false }
        )
    }
    
    // Show video player dialog when video is tapped
    if (showVideoPlayer && mediaMessage.msgType == "m.video") {
        VideoPlayerDialog(
            mediaMessage = mediaMessage,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            isEncrypted = isEncrypted,
            onDismiss = { showVideoPlayer = false }
        )
    }
    // Check if there's a caption to determine layout strategy
    // This determines whether to use separate image frame + caption or single bubble
    val hasCaption = !mediaMessage.caption.isNullOrBlank()
    
    // Calculate if image is small enough to wrap content (like stickers do)
    val density = LocalDensity.current
    val imageWidthDp = if (mediaMessage.info.width > 0 && (mediaMessage.msgType == "m.image" || mediaMessage.msgType == "m.video")) {
        with(density) { mediaMessage.info.width.toDp() }
    } else null
    // If image is small (< 400dp), wrap content instead of using fillMaxWidth(0.8f)
    val shouldWrapBubble = imageWidthDp != null && imageWidthDp < 400.dp
    
    // Check if this is a thread message to apply thread colors
    val isThreadMessage = event?.isThreadMessage() ?: false
    val hasBeenEdited = hasBeenEditedOverride ?: remember(event?.eventId, appViewModel?.timelineUpdateCounter) {
        event?.let { appViewModel?.isMessageEdited(it.eventId) ?: false } ?: false
    }
    val colorScheme = MaterialTheme.colorScheme
    val mediaBubbleColor = bubbleColorOverride ?: mediaBubbleColorFor(
        colorScheme = colorScheme,
        isMine = isMine,
        isThreadMessage = isThreadMessage,
        hasBeenEdited = hasBeenEdited
    )
    
    if (hasCaption) {
        // With caption: Image inside the caption bubble
        if (event != null) {
            MessageBubbleWithMenu(
                event = event,
                bubbleColor = mediaBubbleColor,
                bubbleShape = RoundedCornerShape(
                    topStart = if (isMine) 12.dp else 4.dp,
                    topEnd = if (isMine) 4.dp else 12.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp
                ),
                modifier = modifier
                    .then(
                        if (shouldWrapBubble) {
                            Modifier.wrapContentWidth()
                        } else {
                            Modifier.fillMaxWidth(0.8f)
                        }
                    ),
                isMine = isMine,
                myUserId = myUserId,
                powerLevels = powerLevels,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                appViewModel = appViewModel,
                onBubbleClick = onBubbleClick,
                onShowEditHistory = onShowEditHistory,
                externalMenuTrigger = triggerMenuFromImage
            ) {
                Column {
                    // Image content inside the caption bubble
                    MediaContent(
                        mediaMessage = mediaMessage,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isEncrypted = isEncrypted,
                        loadThumbnailsIfAvailable = useThumbnails,
                        isMine = isMine,
                        onImageClick = { 
                            if (mediaMessage.msgType == "m.video") {
                                showVideoPlayer = true
                            } else {
                                showImageViewer = true
                            }
                        },
                        onImageLongPress = {
                            // Trigger menu from image long press
                            triggerMenuFromImage++
                        }
                    )
                    
                    // Caption text below the image, inside the same bubble (always show filename if no caption)
                    MediaCaption(
                        caption = mediaMessage.caption,
                        filename = mediaMessage.filename,
                        event = event,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onUserClick = onUserClick,
                        isCompactMedia = mediaMessage.msgType == "m.audio" || mediaMessage.msgType == "m.file",
                        appViewModel = appViewModel
                    )
                    
                    // Timestamp (for consecutive messages)
                    if (timestamp != null) {
                        MediaBubbleTimestamp(
                            timestamp = timestamp,
                            editedBy = editedBy,
                            isMine = isMine,
                            isConsecutive = isConsecutive,
                            onEditedClick = onShowEditHistory
                        )
                    }
                }
            }
        } else {
            // Detect dark mode for custom shadow/glow
            val isDarkMode = isSystemInDarkTheme()
            val bubbleShape = RoundedCornerShape(
                topStart = if (isMine) 12.dp else 4.dp,
                topEnd = if (isMine) 4.dp else 12.dp,
                bottomStart = 12.dp,
                bottomEnd = 12.dp
            )
            
            Surface(
                modifier = modifier
                    .then(
                        if (shouldWrapBubble) {
                            Modifier.wrapContentWidth()
                        } else {
                            Modifier.fillMaxWidth(0.8f)
                        }
                    )
                    // In dark mode, add a light glow effect
                    .then(
                        if (isDarkMode) {
                            Modifier.shadow(
                                elevation = 3.dp,
                                shape = bubbleShape,
                                ambientColor = Color.White.copy(alpha = 0.15f), // Light glow in dark mode
                                spotColor = Color.White.copy(alpha = 0.2f)
                            )
                        } else {
                            Modifier
                        }
                    ),
                shape = bubbleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp,  // Provides color changes for elevation
                shadowElevation = if (isDarkMode) 0.dp else 3.dp  // Shadows in light mode only
            ) {
                Column {
                    // Image content inside the caption bubble
                    MediaContent(
                        mediaMessage = mediaMessage,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isEncrypted = isEncrypted,
                        loadThumbnailsIfAvailable = useThumbnails,
                        isMine = isMine,
                        onImageClick = { 
                            if (mediaMessage.msgType == "m.video") {
                                showVideoPlayer = true
                            } else {
                                showImageViewer = true
                            }
                        },
                        onImageLongPress = {
                            // Trigger menu from image long press
                            triggerMenuFromImage++
                        }
                    )
                    
                    // Caption text below the image, inside the same bubble (always show filename if no caption)
                    MediaCaption(
                        caption = mediaMessage.caption,
                        filename = mediaMessage.filename,
                        event = event,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onUserClick = onUserClick,
                        isCompactMedia = mediaMessage.msgType == "m.audio" || mediaMessage.msgType == "m.file",
                        appViewModel = appViewModel
                    )
                    
                    // Timestamp (for consecutive messages)
                    if (timestamp != null) {
                        MediaBubbleTimestamp(
                            timestamp = timestamp,
                            editedBy = editedBy,
                            isMine = isMine,
                            isConsecutive = isConsecutive,
                            onEditedClick = onShowEditHistory
                        )
                    }
                }
            }
        }
    } else {
        // Without caption: Image directly in message bubble with pointed corners
        if (event != null) {
            MessageBubbleWithMenu(
                event = event,
                bubbleColor = mediaBubbleColor,
                bubbleShape = RoundedCornerShape(
                    topStart = if (isMine) 12.dp else 4.dp,
                    topEnd = if (isMine) 4.dp else 12.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp
                ),
                modifier = modifier
                    .then(
                        if (shouldWrapBubble) {
                            Modifier.wrapContentWidth()
                        } else {
                            Modifier.fillMaxWidth(0.8f)
                        }
                    )
                    .wrapContentHeight(),
                isMine = isMine,
                myUserId = myUserId,
                powerLevels = powerLevels,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                appViewModel = appViewModel,
                onBubbleClick = onBubbleClick,
                onShowEditHistory = onShowEditHistory,
                externalMenuTrigger = triggerMenuFromImage
            ) {
                Column {
                    MediaContent(
                        mediaMessage = mediaMessage,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isEncrypted = isEncrypted,
                        loadThumbnailsIfAvailable = useThumbnails,
                        isMine = isMine,
                        onImageClick = { 
                            if (mediaMessage.msgType == "m.video") {
                                showVideoPlayer = true
                            } else {
                                showImageViewer = true
                            }
                        },
                        onImageLongPress = {
                            // Trigger menu from image long press
                            triggerMenuFromImage++
                        }
                    )
                    
                    // Always show filename (even if no caption)
                    MediaCaption(
                        caption = null,
                        filename = mediaMessage.filename,
                        event = event,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onUserClick = onUserClick,
                        isCompactMedia = mediaMessage.msgType == "m.audio" || mediaMessage.msgType == "m.file",
                        appViewModel = appViewModel
                    )
                    
                    // Timestamp (for consecutive messages)
                    if (timestamp != null) {
                        MediaBubbleTimestamp(
                            timestamp = timestamp,
                            editedBy = editedBy,
                            isMine = isMine,
                            isConsecutive = isConsecutive,
                            onEditedClick = onShowEditHistory
                        )
                    }
                }
            }
        } else {
            // Detect dark mode for custom shadow/glow
            val isDarkMode = isSystemInDarkTheme()
            val bubbleShape = RoundedCornerShape(
                topStart = if (isMine) 12.dp else 4.dp,
                topEnd = if (isMine) 4.dp else 12.dp,
                bottomStart = 12.dp,
                bottomEnd = 12.dp
            )
            
            Surface(
                modifier = modifier
                    .then(
                        if (shouldWrapBubble) {
                            Modifier.wrapContentWidth()
                        } else {
                            Modifier.fillMaxWidth(0.8f) // Max 80% width
                        }
                    )
                    .wrapContentHeight()
                    // In dark mode, add a light glow effect
                    .then(
                        if (isDarkMode) {
                            Modifier.shadow(
                                elevation = 3.dp,
                                shape = bubbleShape,
                                ambientColor = Color.White.copy(alpha = 0.15f), // Light glow in dark mode
                                spotColor = Color.White.copy(alpha = 0.2f)
                            )
                        } else {
                            Modifier
                        }
                    ),
                shape = bubbleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp,  // Provides color changes for elevation
                shadowElevation = if (isDarkMode) 0.dp else 3.dp  // Shadows in light mode only
            ) {
                Column {
                    MediaContent(
                        mediaMessage = mediaMessage,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isEncrypted = isEncrypted,
                        loadThumbnailsIfAvailable = useThumbnails,
                        isMine = isMine,
                        onImageClick = { 
                            if (mediaMessage.msgType == "m.video") {
                                showVideoPlayer = true
                            } else {
                                showImageViewer = true
                            }
                        },
                        onImageLongPress = {
                            // Trigger menu from image long press
                            triggerMenuFromImage++
                        }
                    )
                    
                    // Always show filename (even if no caption)
                    MediaCaption(
                        caption = null,
                        filename = mediaMessage.filename,
                        event = event,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onUserClick = onUserClick,
                        isCompactMedia = mediaMessage.msgType == "m.audio" || mediaMessage.msgType == "m.file",
                        appViewModel = appViewModel
                    )
                    
                    // Timestamp (for consecutive messages)
                    if (timestamp != null) {
                        MediaBubbleTimestamp(
                            timestamp = timestamp,
                            editedBy = editedBy,
                            isMine = isMine,
                            isConsecutive = isConsecutive,
                            onEditedClick = onShowEditHistory
                        )
                    }
                }
            }
        }
    }
}

fun mediaBubbleColorFor(
    colorScheme: ColorScheme,
    isMine: Boolean,
    isThreadMessage: Boolean,
    hasBeenEdited: Boolean
): Color {
    return BubblePalette.colors(
        colorScheme = colorScheme,
        isMine = isMine,
        isEdited = hasBeenEdited,
        isThreadMessage = isThreadMessage
    ).container
}

/**
 * Extracted media content composable to avoid code duplication.
 * 
 * This function contains the actual media rendering logic (images/videos)
 * and is shared between the caption and non-caption layouts.
 * 
 * @param mediaMessage MediaMessage object containing URL, filename, media info
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing media
 * @param isEncrypted Whether this is encrypted media (adds ?encrypted=true to URL)
 */
@Composable
private fun MediaContent(
    mediaMessage: MediaMessage,
    homeserverUrl: String,
    authToken: String,
    isEncrypted: Boolean,
    loadThumbnailsIfAvailable: Boolean,
    isMine: Boolean = false, // For determining bubble shape
    onImageClick: () -> Unit = {},
    onImageLongPress: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    
    // Determine if we're using thumbnails
    val useThumbnail = loadThumbnailsIfAvailable && mediaMessage.info.thumbnailUrl != null
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        if (mediaMessage.msgType == "m.audio") {
            // Audio player - use its own sizing without aspect ratio container
            AudioPlayer(
                mediaMessage = mediaMessage,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = isEncrypted,
                context = LocalContext.current
            )
        } else if (mediaMessage.msgType == "m.file") {
            // File download component - use its own sizing without aspect ratio container
            FileDownload(
                mediaMessage = mediaMessage,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = isEncrypted,
                context = LocalContext.current
            )
        } else {
            // Media container with aspect ratio for images and videos
            // If using thumbnails: try thumbnail dimensions first, fallback to full image dimensions if thumbnail dimensions not available
            // If not using thumbnails: use full image dimensions
            val aspectRatio = if (useThumbnail) {
                // First try thumbnail dimensions
                if (mediaMessage.info.thumbnailWidth != null && mediaMessage.info.thumbnailHeight != null && 
                    mediaMessage.info.thumbnailWidth!! > 0 && mediaMessage.info.thumbnailHeight!! > 0) {
                    mediaMessage.info.thumbnailWidth!!.toFloat() / mediaMessage.info.thumbnailHeight!!.toFloat()
                } else if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
                    // Fallback to full image dimensions if thumbnail dimensions not available
                    mediaMessage.info.width.toFloat() / mediaMessage.info.height.toFloat()
                } else {
                    16f / 9f // Default aspect ratio
                }
            } else {
                // Not using thumbnails, use full image dimensions
                if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
                    mediaMessage.info.width.toFloat() / mediaMessage.info.height.toFloat()
                } else {
                    16f / 9f // Default aspect ratio
                }
            }

            // Check if there's a caption
            val hasCaption = !mediaMessage.caption.isNullOrBlank()
            
            // Image frame padding: 2dp on left, top, and right (between thumbnail frame and message bubble)
            val imageFramePadding = 2.dp
            // Bottom padding is separate - used for spacing between image frame and caption/filename
            val bottomPadding = 0.dp

            // Calculate actual image size in dp if dimensions are available
            // If using thumbnails: try thumbnail dimensions first, fallback to full image dimensions if thumbnail dimensions not available
            // If not using thumbnails: use full image dimensions
            val imageHeightDp = if (useThumbnail) {
                // First try thumbnail height
                if (mediaMessage.info.thumbnailHeight != null && mediaMessage.info.thumbnailHeight!! > 0) {
                    with(density) { mediaMessage.info.thumbnailHeight!!.toDp() }
                } else if (mediaMessage.info.height > 0) {
                    // Fallback to full image height if thumbnail height not available
                    with(density) { mediaMessage.info.height.toDp() }
                } else null
            } else {
                // Not using thumbnails, use full image height
                if (mediaMessage.info.height > 0) {
                    with(density) { mediaMessage.info.height.toDp() }
                } else null
            }

            // Image frame with border and padding (2dp on left, top, right)
            // Use same rounded corners as message bubble (top corners only)
            val imageFrameShape = RoundedCornerShape(
                topStart = if (isMine) 12.dp else 4.dp,
                topEnd = if (isMine) 4.dp else 12.dp,
                bottomStart = 0.dp,
                bottomEnd = 0.dp
            )
            
            // Frame always fills bubble width to accommodate caption size
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = imageFramePadding,
                        end = imageFramePadding,
                        top = imageFramePadding
                        // No bottom padding - caption/filename will provide spacing
                    ),
                shape = imageFrameShape,
                color = Color.Transparent,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) // Visible but subtle
                )
            ) {
                // Box to clip the image to frame shape
                // Image will fill the frame width and be clipped by the frame
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(imageFrameShape),
                    contentAlignment = Alignment.Center
                ) {
                    val context = LocalContext.current
                    val coroutineScope = rememberCoroutineScope()

                    // Use shared ImageLoader singleton with custom User-Agent
                    val imageLoader = remember { ImageLoaderSingleton.get(context) }

                    // PERFORMANCE: Use IntelligentMediaCache for smart caching
                    var cachedFile by remember { mutableStateOf<File?>(null) }
                    val displayMxcUrl = remember(mediaMessage.url, mediaMessage.info.thumbnailUrl, useThumbnail) {
                        if (useThumbnail) mediaMessage.info.thumbnailUrl!! else mediaMessage.url
                    }
                    val displayIsEncrypted = if (useThumbnail) {
                        mediaMessage.info.thumbnailIsEncrypted
                    } else {
                        isEncrypted
                    }

                    // Load cached file in background for the chosen resource (thumbnail or full)
                    LaunchedEffect(displayMxcUrl) {
                        cachedFile = IntelligentMediaCache.getCachedFile(context, displayMxcUrl)
                    }

                    val imageUrl =
                        remember(displayMxcUrl, displayIsEncrypted, cachedFile, mediaMessage.url) {
                            if (cachedFile != null) {
                                // Use cached file
                                if (BuildConfig.DEBUG) Log.d(
                                    "Andromuks",
                                    "MediaMessage: Using cached file for display resource: ${cachedFile!!.absolutePath}"
                                )
                                cachedFile!!.absolutePath
                            } else {
                                // Use HTTP URL (thumbnail first, fallback to full if conversion fails)
                                val targetHttp =
                                    MediaUtils.mxcToHttpUrl(displayMxcUrl, homeserverUrl)
                                        ?: MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
                                if (displayIsEncrypted && targetHttp != null) {
                                    "$targetHttp?encrypted=true"
                                } else {
                                    targetHttp
                                }
                            }
                        }

                    // NOTE: Coil handles caching automatically with memoryCachePolicy and
                    // diskCachePolicy
                    // No need to manually download - would cause duplicate requests (Coil + okhttp)

                    if (mediaMessage.msgType == "m.image") {
                        // Debug logging
                        if (BuildConfig.DEBUG) Log.d(
                            "Andromuks",
                            "MediaMessage: URL=$imageUrl, BlurHash=${mediaMessage.info.blurHash}, AuthToken=$authToken"
                        )

                        val blurHashForDisplay =
                            if (useThumbnail) {
                                mediaMessage.info.thumbnailBlurHash ?: mediaMessage.info.blurHash
                            } else {
                                mediaMessage.info.blurHash
                            }

                        if (BuildConfig.DEBUG && blurHashForDisplay != null) {
                            Log.d(
                                "Andromuks",
                                "MediaMessage: Decoding blurhash for display (thumb=$useThumbnail): $blurHashForDisplay"
                            )
                        }

                        val blurHashPainter =
                            remember(blurHashForDisplay) {
                                blurHashForDisplay?.let { blurHash ->
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "Decoding BlurHash: $blurHash")
                                    val bitmap = BlurHashUtils.decodeBlurHash(blurHash, 32, 32)
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "BlurHash decoded: ${bitmap != null}")
                                    if (bitmap != null) {
                                        val imageBitmap = bitmap.asImageBitmap()
                                        if (BuildConfig.DEBUG) Log.d(
                                            "Andromuks",
                                            "BlurHash converted to ImageBitmap: ${imageBitmap.width}x${imageBitmap.height}"
                                        )
                                        if (BuildConfig.DEBUG) Log.d(
                                            "Andromuks",
                                            "BlurHash bitmap info: config=${bitmap.config}, hasAlpha=${bitmap.hasAlpha()}"
                                        )
                                        BitmapPainter(imageBitmap)
                                    } else {
                                        Log.w("Andromuks", "BlurHash decode failed, using fallback")
                                        BitmapPainter(
                                            BlurHashUtils.createPlaceholderBitmap(
                                                32,
                                                32,
                                                androidx.compose.ui.graphics.Color.Gray
                                            )
                                        )
                                    }
                                }
                                    ?: run {
                                        // Simple fallback placeholder without MaterialTheme
                                        if (BuildConfig.DEBUG) Log.d(
                                            "Andromuks",
                                            "No BlurHash available, using simple fallback"
                                        )
                                        BitmapPainter(
                                            BlurHashUtils.createPlaceholderBitmap(
                                                32,
                                                32,
                                                androidx.compose.ui.graphics.Color.Gray
                                            )
                                        )
                                    }
                            }

                        if (BuildConfig.DEBUG) Log.d("Andromuks", "BlurHash painter created")

                        if (BuildConfig.DEBUG) {
                            val fullWidth = mediaMessage.info.width
                            val fullHeight = mediaMessage.info.height
                            val thumbWidth = mediaMessage.info.thumbnailWidth
                            val thumbHeight = mediaMessage.info.thumbnailHeight
                            Log.d("Andromuks", "AsyncImage: Starting image load for $imageUrl")
                            Log.d("Andromuks", "AsyncImage: useThumbnail=$useThumbnail, aspectRatio=$aspectRatio")
                            Log.d("Andromuks", "AsyncImage: Full image dimensions: ${fullWidth}x${fullHeight}, Thumbnail dimensions: ${thumbWidth}x${thumbHeight}")
                        }

                        // State to track loaded image dimensions for dynamic aspect ratio adjustment
                        // Only update if JSON dimensions were missing or invalid
                        var loadedAspectRatio by remember { mutableFloatStateOf(aspectRatio) }
                        var hasLoadedDimensions by remember { mutableStateOf(false) }
                        
                        // Check if JSON dimensions were actually valid
                        val hasValidJsonDimensions = if (useThumbnail) {
                            (mediaMessage.info.thumbnailWidth != null && mediaMessage.info.thumbnailWidth!! > 0 && 
                             mediaMessage.info.thumbnailHeight != null && mediaMessage.info.thumbnailHeight!! > 0) ||
                            (mediaMessage.info.width > 0 && mediaMessage.info.height > 0)
                        } else {
                            mediaMessage.info.width > 0 && mediaMessage.info.height > 0
                        }
                        
                        // Reset when image URL changes
                        LaunchedEffect(imageUrl) {
                            loadedAspectRatio = aspectRatio
                            hasLoadedDimensions = false
                        }

                        // PERFORMANCE: Use AsyncImage with onSuccess to extract dimensions and adjust aspect ratio
                        // Image fills frame width and maintains aspect ratio, clipped by frame
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUrl ?: "")
                                .apply {
                                    if (cachedFile == null) {
                                        addHeader("Cookie", "gomuks_auth=$authToken")
                                    }
                                }
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .size(600, 600) // QUALITY IMPROVEMENT: Larger size for better quality
                                .build(),
                            imageLoader = imageLoader,
                            contentDescription = mediaMessage.filename,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(loadedAspectRatio)
                                .scale(1.02f) // Make image slightly larger than frame so it gets clipped
                                .combinedClickable(
                                    onClick = { onImageClick() },
                                    onLongClick = { onImageLongPress?.invoke() }
                                ),
                            placeholder = blurHashPainter,
                            error = blurHashPainter,
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth, // Fill frame width, maintain aspect ratio
                            onSuccess = { state ->
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "✅ Image loaded successfully: $imageUrl")
                                
                                        // Extract actual image dimensions from loaded image
                                        val painter = state.painter
                                        val intrinsicSize = painter.intrinsicSize
                                        if (intrinsicSize.width > 0 && intrinsicSize.height > 0 && !hasLoadedDimensions) {
                                            val actualAspectRatio = intrinsicSize.width / intrinsicSize.height
                                            if (BuildConfig.DEBUG) Log.d(
                                                "Andromuks",
                                                "Image loaded with dimensions: ${intrinsicSize.width}x${intrinsicSize.height}, aspectRatio=$actualAspectRatio (original from JSON: $aspectRatio, hasValidJsonDimensions: $hasValidJsonDimensions)"
                                            )
                                            // Only update if we didn't have valid dimensions from JSON
                                            // This means JSON width/height were missing or invalid
                                            if (!hasValidJsonDimensions) {
                                                loadedAspectRatio = actualAspectRatio
                                                hasLoadedDimensions = true
                                                if (BuildConfig.DEBUG) Log.d(
                                                    "Andromuks",
                                                    "Updated aspect ratio from loaded image: $actualAspectRatio"
                                                )
                                            }
                                        }
                            },
                            onError = { },
                            onLoading = { state ->
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "⏳ Image loading: $imageUrl, state: $state")
                            }
                        )
                    } else if (mediaMessage.msgType == "m.video") {
                        // Video thumbnail with play button overlay
                        // Fill frame width and maintain aspect ratio, clipped by frame
                        
                        // State to track loaded thumbnail dimensions for dynamic aspect ratio adjustment
                        var loadedThumbnailAspectRatio by remember { mutableFloatStateOf(aspectRatio) }
                        var hasLoadedThumbnailDimensions by remember { mutableStateOf(false) }
                        
                        // Check if video has a thumbnail
                        val thumbnailUrl = mediaMessage.info.thumbnailUrl
                        
                        // Check if JSON dimensions were actually valid for video
                        val hasValidVideoJsonDimensions = 
                            (mediaMessage.info.thumbnailWidth != null && mediaMessage.info.thumbnailWidth!! > 0 && 
                             mediaMessage.info.thumbnailHeight != null && mediaMessage.info.thumbnailHeight!! > 0) ||
                            (mediaMessage.info.width > 0 && mediaMessage.info.height > 0)
                        
                        // Reset when thumbnail URL changes
                        LaunchedEffect(thumbnailUrl) {
                            loadedThumbnailAspectRatio = aspectRatio
                            hasLoadedThumbnailDimensions = false
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(loadedThumbnailAspectRatio)
                                .scale(1.02f), // Make thumbnail slightly larger than frame so it gets clipped
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumbnailUrl != null) {
                                // Render video thumbnail
                                val thumbnailHttpUrl =
                                    MediaUtils.mxcToHttpUrl(thumbnailUrl, homeserverUrl)
                                val thumbnailFinalUrl =
                                    if (
                                        mediaMessage.info.thumbnailIsEncrypted &&
                                            thumbnailHttpUrl != null
                                    ) {
                                        "$thumbnailHttpUrl?encrypted=true"
                                    } else {
                                        thumbnailHttpUrl
                                    }

                                val thumbnailBlurHashPainter =
                                    remember(mediaMessage.info.thumbnailBlurHash) {
                                        mediaMessage.info.thumbnailBlurHash?.let { blurHash ->
                                            val bitmap =
                                                BlurHashUtils.decodeBlurHash(blurHash, 32, 32)
                                            if (bitmap != null) {
                                                BitmapPainter(bitmap.asImageBitmap())
                                            } else {
                                                BitmapPainter(
                                                    BlurHashUtils.createPlaceholderBitmap(
                                                        32,
                                                        32,
                                                        androidx.compose.ui.graphics.Color.Gray
                                                    )
                                                )
                                            }
                                        }
                                            ?: BitmapPainter(
                                                BlurHashUtils.createPlaceholderBitmap(
                                                    32,
                                                    32,
                                                    androidx.compose.ui.graphics.Color.Gray
                                                )
                                            )
                                    }

                                AsyncImage(
                                    model =
                                        ImageRequest.Builder(context)
                                            .data(thumbnailFinalUrl)
                                            .addHeader("Cookie", "gomuks_auth=$authToken")
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .size(600, 600) // QUALITY IMPROVEMENT: Larger size for better quality
                                            .build(),
                                    imageLoader = imageLoader,
                                    contentDescription =
                                        "Video thumbnail: ${mediaMessage.filename}",
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .combinedClickable(
                                                onClick = { onImageClick() },
                                                onLongClick = { onImageLongPress?.invoke() }
                                            ),
                                    placeholder = thumbnailBlurHashPainter,
                                    error = thumbnailBlurHashPainter,
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth, // Fill frame width, maintain aspect ratio
                                    onSuccess = { state ->
                                        if (BuildConfig.DEBUG) Log.d(
                                            "Andromuks",
                                            "✅ Video thumbnail loaded: $thumbnailFinalUrl"
                                        )
                                        
                                        // Extract actual thumbnail dimensions from loaded image
                                        val painter = state.painter
                                        val intrinsicSize = painter.intrinsicSize
                                        if (intrinsicSize.width > 0 && intrinsicSize.height > 0 && !hasLoadedThumbnailDimensions) {
                                            val actualAspectRatio = intrinsicSize.width / intrinsicSize.height
                                            if (BuildConfig.DEBUG) Log.d(
                                                "Andromuks",
                                                "Video thumbnail loaded with dimensions: ${intrinsicSize.width}x${intrinsicSize.height}, aspectRatio=$actualAspectRatio (original from JSON: $aspectRatio, hasValidJsonDimensions: $hasValidVideoJsonDimensions)"
                                            )
                                            // Only update if we didn't have valid dimensions from JSON
                                            if (!hasValidVideoJsonDimensions) {
                                                loadedThumbnailAspectRatio = actualAspectRatio
                                                hasLoadedThumbnailDimensions = true
                                                if (BuildConfig.DEBUG) Log.d(
                                                    "Andromuks",
                                                    "Updated video thumbnail aspect ratio from loaded image: $actualAspectRatio"
                                                )
                                            }
                                        }
                                    },
                                    onError = { }
                                )

                                // Duration badge in bottom-right corner
                                mediaMessage.info.duration?.let { durationMs ->
                                    Box(
                                        modifier = Modifier.fillMaxSize().padding(8.dp),
                                        contentAlignment = Alignment.BottomEnd
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color.Black.copy(alpha = 0.7f)
                                        ) {
                                            Text(
                                                text = formatDuration(durationMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                modifier =
                                                    Modifier.padding(
                                                        horizontal = 4.dp,
                                                        vertical = 2.dp
                                                    )
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Fallback: No thumbnail available, show placeholder
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(aspectRatio)
                                        .combinedClickable(
                                            onClick = { onImageClick() },
                                            onLongClick = { onImageLongPress?.invoke() }
                                        )
                                ) {
                                    Text(
                                        text = "🎥",
                                        style = MaterialTheme.typography.headlineMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = mediaMessage.filename,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * Audio player component for m.audio messages with Material 3 design.
 * Features play/pause button, progress slider, and duration display.
 */
@Composable
private fun AudioPlayer(
    mediaMessage: MediaMessage,
    homeserverUrl: String,
    authToken: String,
    isEncrypted: Boolean,
    context: android.content.Context
) {
    // Convert MXC URL to HTTP URL
    val audioHttpUrl = remember(mediaMessage.url, isEncrypted) {
        val httpUrl = MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
        if (isEncrypted) {
            "$httpUrl?encrypted=true"
        } else {
            httpUrl ?: ""
        }
    }
    
    // ExoPlayer instance for audio playback
    val exoPlayer = remember {
        // Create MediaItem with authentication headers
        val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Cookie" to "gomuks_auth=$authToken"))
        
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
        
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                // Set up player with custom data source
                val mediaItem = androidx.media3.common.MediaItem.fromUri(audioHttpUrl)
                setMediaItem(mediaItem)
                prepare()
            }
    }
    
    // Dispose player when component is removed
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }
    
    // State for play/pause and progress
    var isPlaying by remember { mutableStateOf(false) }
    var duration by remember { mutableStateOf(0L) }
    var currentPosition by remember { mutableStateOf(0L) }
    
    // Listen to player state changes
    LaunchedEffect(exoPlayer) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            duration = exoPlayer.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L
            currentPosition = exoPlayer.currentPosition
            kotlinx.coroutines.delay(100) // Update every 100ms
        }
    }
    
    // Audio player UI
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp) // Fixed height for audio player
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            // Top row: filename and duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = mediaMessage.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                Text(
                    text = if (duration > 0) {
                        "${formatDurationMs(duration.toInt())}"
                    } else {
                        mediaMessage.info.duration?.let { formatDuration(it) } ?: "--:--"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Bottom row: play button and progress slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Pause button
                IconButton(
                    onClick = {
                        if (isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Progress slider
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { progress ->
                            if (duration > 0) {
                                val newPosition = (progress * duration).toLong()
                                exoPlayer.seekTo(newPosition)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                    )
                    
                    // Current position and duration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDurationMs(currentPosition.toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Text(
                            text = if (duration > 0) {
                                formatDurationMs(duration.toInt())
                            } else {
                                mediaMessage.info.duration?.let { formatDuration(it) } ?: "--:--"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * File download component for m.file messages with Material 3 design.
 * Features file icon, filename, size, mimetype, and download button.
 */
@Composable
private fun FileDownload(
    mediaMessage: MediaMessage,
    homeserverUrl: String,
    authToken: String,
    isEncrypted: Boolean,
    context: android.content.Context
) {
    // Convert MXC URL to HTTP URL
    val fileHttpUrl = remember(mediaMessage.url, isEncrypted) {
        val httpUrl = MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
        if (isEncrypted) {
            "$httpUrl?encrypted=true"
        } else {
            httpUrl ?: ""
        }
    }
    
    val coroutineScope = rememberCoroutineScope()
    
    // File download UI
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // File icon
        @Suppress("DEPRECATION")
        Icon(
            imageVector = Icons.Filled.InsertDriveFile,
            contentDescription = "File",
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        // File info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // Filename
            Text(
                text = mediaMessage.filename,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Size and mimetype
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // File size
                mediaMessage.info.size?.let { sizeBytes ->
                    Text(
                        text = formatFileSize(sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // MIME type
                mediaMessage.info.mimeType?.let { mimeType ->
                    Text(
                        text = mimeType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
        
        // Download button
        IconButton(
            onClick = {
                coroutineScope.launch {
                    downloadFile(
                        context = context,
                        url = fileHttpUrl,
                        filename = mediaMessage.filename,
                        authToken = authToken
                    )
                }
            },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Download file using OkHttp and Android DownloadManager
 */
private suspend fun downloadFile(
    context: android.content.Context,
    url: String,
    filename: String,
    authToken: String
) {
    try {
        // Use Android DownloadManager for system-level download handling
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
        
        // Set authentication header
        request.addRequestHeader("Cookie", "gomuks_auth=$authToken")
        
        // Set destination directory (Downloads folder)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, filename)
        
        // Handle duplicate filenames
        var finalFile = file
        var counter = 1
        while (finalFile.exists()) {
            val nameWithoutExt = filename.substringBeforeLast(".")
            val extension = if (filename.contains(".")) filename.substringAfterLast(".") else ""
            val newFilename = if (extension.isNotEmpty()) {
                "$nameWithoutExt ($counter).$extension"
            } else {
                "$filename ($counter)"
            }
            finalFile = File(downloadsDir, newFilename)
            counter++
        }
        
        request.setDestinationUri(Uri.fromFile(finalFile))
        request.setTitle("Downloading $filename")
        request.setDescription("Downloading file from Andromuks")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        
        downloadManager.enqueue(request)
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "File download started: $filename to ${finalFile.absolutePath}")
        
    } catch (e: Exception) {
        Log.e("Andromuks", "Failed to download file: $filename", e)
        // Fallback: Try using OkHttp for download
        try {
            downloadFileWithOkHttp(context, url, filename, authToken)
        } catch (fallbackException: Exception) {
            Log.e("Andromuks", "Fallback download also failed: $filename", fallbackException)
        }
    }
}

/**
 * Fallback download using OkHttp when DownloadManager fails
 */
private suspend fun downloadFileWithOkHttp(
    context: android.content.Context,
    url: String,
    filename: String,
    authToken: String
) {
    try {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", "gomuks_auth=$authToken")
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, filename)
            
            // Handle duplicate filenames
            var finalFile = file
            var counter = 1
            while (finalFile.exists()) {
                val nameWithoutExt = filename.substringBeforeLast(".")
                val extension = if (filename.contains(".")) filename.substringAfterLast(".") else ""
                val newFilename = if (extension.isNotEmpty()) {
                    "$nameWithoutExt ($counter).$extension"
                } else {
                    "$filename ($counter)"
                }
                finalFile = File(downloadsDir, newFilename)
                counter++
            }
            
            response.body?.byteStream()?.use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "File downloaded successfully: $filename to ${finalFile.absolutePath}")
        }
    } catch (e: Exception) {
        Log.e("Andromuks", "OkHttp download failed for $filename", e)
        throw e
    }
}

/**
 * Format file size in bytes to human readable format
 */
private fun formatFileSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    
    return when {
        bytes >= gb -> String.format("%.1f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

/**
 * Format duration from milliseconds to MM:SS or HH:MM:SS format
 */
private fun formatDurationMs(durationMs: Int): String {
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}

/**
 * Save image to device gallery using MediaStore
 */
private suspend fun saveImageToGallery(
    context: Context,
    bitmap: android.graphics.Bitmap?,
    cachedFile: File?,
    imageUrl: String,
    filename: String?,
    authToken: String
) = withContext(Dispatchers.IO) {
    try {
        var imageFile: File? = cachedFile
        
        // If we don't have bitmap or cached file, download the image
        if (bitmap == null && imageFile == null) {
            val httpUrl = if (imageUrl.startsWith("http")) {
                imageUrl
            } else if (imageUrl.startsWith("/")) {
                // Local file path
                imageFile = File(imageUrl)
                null
            } else {
                // If it's a file path, try to use it
                imageFile = File(imageUrl)
                null
            }
            
            if (httpUrl != null) {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(httpUrl)
                    .addHeader("Cookie", "gomuks_auth=$authToken")
                    .build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("Failed to download image: ${response.code}")
                }
                
                response.body?.byteStream()?.use { input ->
                    val tempFile = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                    imageFile = tempFile
                }
            }
        }
        
        // Determine filename
        val displayName = filename?.takeIf { it.isNotBlank() } 
            ?: "image_${System.currentTimeMillis()}.jpg"
        val finalFilename = if (!displayName.contains(".")) {
            "$displayName.jpg"
        } else {
            displayName
        }
        
        // Save to MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Andromuks")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw Exception("Failed to create MediaStore entry")
        
        // Write image data
        context.contentResolver.openOutputStream(uri)?.use { output ->
            if (bitmap != null) {
                // Save bitmap
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, output)
            } else if (imageFile != null && imageFile.exists()) {
                // Copy file
                imageFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } else {
                throw Exception("No image data available")
            }
        }
        
        // Mark as not pending (Android Q+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }
        
        // Show success toast
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                "Image saved to gallery",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "Image saved to gallery: $finalFilename")
    } catch (e: Exception) {
        Log.e("Andromuks", "Failed to save image to gallery", e)
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                "Failed to save image: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/**
 * Fullscreen image viewer dialog with zoom and pan capabilities.
 * 
 * This dialog provides a fullscreen image viewing experience with:
 * - Pinch to zoom functionality
 * - Pan gestures when zoomed
 * - Close button and back gesture support
 * - Smooth animations and transitions
 * 
 * @param mediaMessage MediaMessage object containing the image to display
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing media
 * @param isEncrypted Whether this is encrypted media (adds ?encrypted=true to URL)
 * @param onDismiss Callback when the dialog should be dismissed
 */
@Composable
private fun ImageViewerDialog(
    mediaMessage: MediaMessage,
    homeserverUrl: String,
    authToken: String,
    isEncrypted: Boolean,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // Track cumulative rotation (can be any value, not just 0-360) to avoid wrap-around animation issues
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    
    // Animate rotation smoothly - normalize to 0-360 range only for rendering
    val animatedRotation by animateFloatAsState(
        targetValue = rotationDegrees,
        animationSpec = tween(durationMillis = 300), // 300ms animation
        label = "rotation"
    )
    
    // Normalize rotation to 0-360 range for rendering (handles wrap-around correctly)
    val normalizedRotation = (animatedRotation % 360f + 360f) % 360f
    
    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        // Keep pan speed consistent regardless of zoom level by scaling the offset
        val panScale = scale
        val maxPan = 4000f * scale
        offsetX = (offsetX + offsetChange.x * panScale).coerceIn(-maxPan, maxPan)
        offsetY = (offsetY + offsetChange.y * panScale).coerceIn(-maxPan, maxPan)
    }
    
    val coroutineScope = rememberCoroutineScope()
    
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false, // We handle this manually
                    usePlatformDefaultWidth = false
                )
            ) {
                // Pure black background - tapping it dismisses the dialog
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable(onClick = onDismiss) // Tap background to dismiss
                ) {
                    // Image with zoom and pan
                    val context = LocalContext.current
                    
                    // Use shared ImageLoader singleton with custom User-Agent
                    val imageLoader = remember { ImageLoaderSingleton.get(context) }
                    
                    // Check if we have a cached version first
                    var cachedFile by remember { mutableStateOf<File?>(null) }
                    LaunchedEffect(mediaMessage.url) {
                        cachedFile = IntelligentMediaCache.getCachedFile(context, mediaMessage.url)
                    }
                    
                    val imageUrl = remember(mediaMessage.url, isEncrypted, cachedFile) {
                        val file = cachedFile
                        if (file != null) {
                            // Use cached file
                            file.absolutePath
                        } else {
                            // Use HTTP URL
                            val httpUrl = MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
                            if (isEncrypted) {
                                "$httpUrl?encrypted=true"
                            } else {
                                httpUrl ?: ""
                            }
                        }
                    }
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                                rotationZ = normalizedRotation // Use normalized animated rotation for smooth transitions
                            )
                            .transformable(state = transformableState)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        // Tap on image: reset zoom and pan to center
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                )
                            }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUrl)
                                .apply {
                                    if (cachedFile == null) {
                                        addHeader("Cookie", "gomuks_auth=$authToken")
                                    }
                                }
                                // Load at full resolution to avoid any downscaling artifacts in the viewer
                                .size(Size.ORIGINAL)
                                .precision(Precision.EXACT)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build(),
                            imageLoader = imageLoader,
                            contentDescription = mediaMessage.filename,
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit, // Maintain aspect ratio, may have letterbox bars
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            onSuccess = { 
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "✅ ImageViewer: Image loaded successfully: $imageUrl")
                            },
                            onError = { }
                        )
                    }
                    
                    // Top toolbar with action buttons
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 8.dp)
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Rotate Left button
                        IconButton(
                            onClick = {
                                // Always subtract 90 (don't normalize here - let animation handle it)
                                rotationDegrees = rotationDegrees - 90f
                                // Reset zoom/pan when rotating
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.RotateLeft,
                                contentDescription = "Rotate Left",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        // Rotate Right button
                        IconButton(
                            onClick = {
                                // Always add 90 (don't normalize here - let animation handle it)
                                rotationDegrees = rotationDegrees + 90f
                                // Reset zoom/pan when rotating
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.RotateRight,
                                contentDescription = "Rotate Right",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        // Save button
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    saveImageToGallery(context, null, cachedFile, imageUrl, mediaMessage.filename, authToken)
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = "Save to Gallery",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        // Close button
                        IconButton(
                            onClick = onDismiss,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
}

/**
 * Fullscreen video player dialog with ExoPlayer.
 * 
 * This dialog provides a fullscreen video playback experience with:
 * - ExoPlayer for smooth video playback
 * - Standard player controls (play/pause, seek, etc.)
 * - Close button and back gesture support
 * - Authentication handling for video URLs
 * 
 * @param mediaMessage MediaMessage object containing the video to play
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing video
 * @param isEncrypted Whether this is encrypted video (adds ?encrypted=true to URL)
 * @param onDismiss Callback when the dialog should be dismissed
 */
@Composable
fun VideoPlayerDialog(
    mediaMessage: MediaMessage,
    homeserverUrl: String,
    authToken: String,
    isEncrypted: Boolean,
    onDismiss: () -> Unit
) {
    // Video player container
    val context = LocalContext.current
    
    // Convert MXC URL to HTTP URL
    val videoHttpUrl = remember(mediaMessage.url, isEncrypted) {
        val httpUrl = MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
        if (isEncrypted) {
            "$httpUrl?encrypted=true"
        } else {
            httpUrl ?: ""
        }
    }
    
    // Track the actual ExoPlayer instance that's playing
    var actualPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    
    Dialog(
        onDismissRequest = {
            // Stop the actual playing player before dismissing the dialog
            actualPlayer?.let { player ->
                player.stop()
                player.release()
            }
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = false, // We handle this manually with BackHandler
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // Handle back button/gesture to stop video and dismiss
        BackHandler {
            // Stop the actual playing player before dismissing
            actualPlayer?.let { player ->
                player.stop()
                player.release()
            }
            onDismiss()
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            
            // Stop and dispose player when dialog is dismissed
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    actualPlayer?.let { player ->
                        player.stop()
                        player.release()
                    }
                }
            }
            
            // Player view
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    // Set custom request headers for authentication
                    val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(mapOf("Cookie" to "gomuks_auth=$authToken"))
                    
                    val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx)
                        .setDataSourceFactory(dataSourceFactory)
                    
                    // Create player with custom data source factory
                    val player = androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
                        .setMediaSourceFactory(mediaSourceFactory)
                        .build()
                        .apply {
                            val mediaItem = androidx.media3.common.MediaItem.fromUri(videoHttpUrl)
                            setMediaItem(mediaItem)
                            prepare()
                            playWhenReady = true
                        }
                    
                    // Store reference to the actual player
                    actualPlayer = player
                    
                    androidx.media3.ui.PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        controllerShowTimeoutMs = 3000
                        controllerHideOnTouch = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Close button overlay (top-right corner)
            IconButton(
                onClick = {
                    // Stop the actual playing player before dismissing
                    actualPlayer?.let { player ->
                        player.stop()
                        player.release()
                    }
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
