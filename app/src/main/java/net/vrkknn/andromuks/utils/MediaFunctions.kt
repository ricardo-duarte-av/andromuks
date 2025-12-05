package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import net.vrkknn.andromuks.TimelineEvent


import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import net.vrkknn.andromuks.utils.OptimizedMediaCache
import net.vrkknn.andromuks.utils.AdvancedExoPlayerManager
import net.vrkknn.andromuks.utils.ProgressiveImageLoader
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.DownloadDeduplicationManager
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.os.Build
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.ImageLoader
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.DisposableEffect
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileOutputStream

/**
 * Media cache utility functions for MXC URLs
 */
object MediaCache {
    private const val CACHE_DIR_NAME = "media_cache"
    private const val MAX_CACHE_SIZE = 4L * 1024 * 1024 * 1024L // 4GB
    
    /**
     * Get cache directory for media files
     */
    fun getCacheDir(context: android.content.Context): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    /**
     * Generate cache key from MXC URL
     */
    fun getCacheKey(mxcUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(mxcUrl.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Get cached file for MXC URL
     */
    fun getCachedFile(context: android.content.Context, mxcUrl: String): File? {
        val cacheDir = getCacheDir(context)
        val cacheKey = getCacheKey(mxcUrl)
        val cachedFile = File(cacheDir, cacheKey)
        return if (cachedFile.exists()) cachedFile else null
    }
    
    /**
     * Check if MXC URL is cached
     */
    fun isCached(context: android.content.Context, mxcUrl: String): Boolean {
        return getCachedFile(context, mxcUrl) != null
    }
    
    /**
     * Download and cache MXC URL
     */
    suspend fun downloadAndCache(
        context: android.content.Context,
        mxcUrl: String,
        httpUrl: String,
        authToken: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getCacheDir(context)
            val cacheKey = getCacheKey(mxcUrl)
            val cachedFile = File(cacheDir, cacheKey)
            
            // Download the file
            val connection = URL(httpUrl).openConnection()
            connection.setRequestProperty("Cookie", "gomuks_auth=$authToken")
            connection.connect()
            
            cachedFile.outputStream().use { output ->
                connection.getInputStream().use { input ->
                    input.copyTo(output)
                }
            }
            
            cachedFile
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "Failed to cache media: $mxcUrl", e)
            null
        }
    }
    
    /**
     * Clean up old cache files if cache size exceeds limit
     */
    fun cleanupCache(context: android.content.Context) {
        try {
            val cacheDir = getCacheDir(context)
            val files = cacheDir.listFiles() ?: return
            
            // Calculate total cache size
            val totalSize = files.sumOf { it.length() }
            
            if (totalSize > MAX_CACHE_SIZE) {
                // Sort by modification time (oldest first)
                val sortedFiles = files.sortedBy { it.lastModified() }
                
                // Remove oldest files until under limit
                var currentSize = totalSize
                for (file in sortedFiles) {
                    if (currentSize <= MAX_CACHE_SIZE) break
                    file.delete()
                    currentSize -= file.length()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "Failed to cleanup cache", e)
        }
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
 * Renders a caption with HTML support when available
 */
@Composable
private fun MediaCaption(
    caption: String,
    event: TimelineEvent?,
    homeserverUrl: String,
    authToken: String,
    onUserClick: (String) -> Unit = {},
    isCompactMedia: Boolean = false, // For audio and file messages
    appViewModel: AppViewModel? = null
) {
    // Check if the event supports HTML rendering (has sanitized_html or formatted_body)
    val supportsHtml = event != null && supportsHtmlRendering(event)
    
    // Use minimal padding for compact media messages (audio and file)
    val padding = if (isCompactMedia) {
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    } else {
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    }
    
    if (supportsHtml && event != null) {
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
        // Fallback to plain text
        Text(
            text = caption,
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
                    
                    // Caption text below the image, inside the same bubble
                    MediaCaption(
                        caption = mediaMessage.caption,
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
                    
                    // Caption text below the image, inside the same bubble
                    MediaCaption(
                        caption = mediaMessage.caption,
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
    return if (isThreadMessage) {
        if (isMine) colorScheme.tertiaryContainer
        else colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    } else if (hasBeenEdited) {
        if (isMine) colorScheme.secondaryContainer
        else colorScheme.surfaceContainerHighest
    } else {
        colorScheme.surfaceVariant
    }
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
    onImageClick: () -> Unit = {},
    onImageLongPress: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    
    // Pre-calculate image dimensions to determine if we should wrap content
    val imageWidthDp = if (mediaMessage.info.width > 0) {
        with(density) { mediaMessage.info.width.toDp() }
    } else null
    
    // Determine if image is small enough to wrap content (estimate < 400dp)
    val shouldWrapContent = imageWidthDp != null && imageWidthDp < 400.dp
    
    Column(
        modifier = Modifier
            .then(
                if (shouldWrapContent && (mediaMessage.msgType == "m.image" || mediaMessage.msgType == "m.video")) {
                    Modifier.wrapContentWidth()
                } else {
                    Modifier.fillMaxWidth()
                }
            ),
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
            val aspectRatio =
                if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
                    mediaMessage.info.width.toFloat() / mediaMessage.info.height.toFloat()
                } else {
                    16f / 9f // Default aspect ratio
                }

            // Check if there's a caption
            val hasCaption = !mediaMessage.caption.isNullOrBlank()
            
            // Very tiny padding for image frame
            val imagePadding = 4.dp
            val bottomPadding = if (hasCaption) 8.dp else imagePadding

            // Calculate actual image size in dp if dimensions are available
            val imageHeightDp = if (mediaMessage.info.height > 0) {
                with(density) { mediaMessage.info.height.toDp() }
            } else null

            BoxWithConstraints(
                modifier = Modifier
                    .then(
                        if (shouldWrapContent) {
                            // Wrap content when image is small, but limit max width
                            Modifier
                                .wrapContentWidth()
                                .widthIn(max = (imageWidthDp ?: 400.dp) + (imagePadding * 2) + 10.dp)
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
            ) {
                // Calculate available width (accounting for padding)
                // When wrapping, maxWidth might be unbounded, so use image width as fallback
                val effectiveMaxWidth = if (shouldWrapContent) {
                    // When wrapping, use image width + padding as the effective max
                    (imageWidthDp ?: 400.dp) + (imagePadding * 2)
                } else {
                    maxWidth
                }
                val availableWidth = effectiveMaxWidth - (imagePadding * 2)
                val maxHeight = 300.dp - (imagePadding + bottomPadding)
                
                // Determine if image should be rendered at actual size or scaled
                // If Column is wrapping, we know image is small, so render at actual size
                val shouldRenderAtActualSize = if (shouldWrapContent) {
                    imageWidthDp != null && imageHeightDp != null
                } else {
                    imageWidthDp != null && imageHeightDp != null &&
                    imageWidthDp <= availableWidth && imageHeightDp <= maxHeight
                }
                
                val calculatedHeight =
                    if (aspectRatio > 0) {
                        if (shouldRenderAtActualSize) {
                            // Use actual image height, but don't exceed maxHeight
                            imageHeightDp!!.coerceAtMost(maxHeight)
                        } else {
                            // Scale down to fit available space
                            (availableWidth / aspectRatio).coerceAtMost(maxHeight)
                        }
                    } else {
                        200.dp // Default height
                    }
                
                val calculatedWidth = if (shouldRenderAtActualSize && imageWidthDp != null) {
                    // Use actual image width, but don't exceed available width
                    imageWidthDp.coerceAtMost(availableWidth)
                } else {
                    availableWidth
                }

                // Image container with padding (frame around image)
                Box(
                    modifier = Modifier
                        .then(
                            if (shouldRenderAtActualSize) {
                                // Wrap content when image is small (don't enlarge)
                                Modifier
                                    .wrapContentWidth()
                                    .wrapContentHeight()
                            } else {
                                // Fill available space when image is large (scale down)
                                Modifier
                                    .fillMaxWidth()
                                    .height(calculatedHeight)
                            }
                        )
                        .padding(
                            start = imagePadding,
                            end = imagePadding,
                            top = imagePadding,
                            bottom = bottomPadding
                        ),
                    contentAlignment = if (shouldRenderAtActualSize) Alignment.TopStart else Alignment.Center
                ) {
                    val context = LocalContext.current
                    val coroutineScope = rememberCoroutineScope()

                    // Use shared ImageLoader singleton with custom User-Agent
                    val imageLoader = remember { ImageLoaderSingleton.get(context) }

                    // PERFORMANCE: Use IntelligentMediaCache for smart caching
                    var cachedFile by remember { mutableStateOf<File?>(null) }
                    
                    // Load cached file in background
                    LaunchedEffect(mediaMessage.url) {
                        cachedFile = IntelligentMediaCache.getCachedFile(context, mediaMessage.url)
                    }

                    val imageUrl =
                        remember(mediaMessage.url, isEncrypted, cachedFile) {
                            if (cachedFile != null) {
                                // Use cached file
                                if (BuildConfig.DEBUG) Log.d(
                                    "Andromuks",
                                    "MediaMessage: Using cached file: ${cachedFile!!.absolutePath}"
                                )
                                cachedFile!!.absolutePath
                            } else {
                                // Use HTTP URL
                                val httpUrl =
                                    MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
                                if (isEncrypted) {
                                    val encryptedUrl = "$httpUrl?encrypted=true"
                                    if (BuildConfig.DEBUG) Log.d(
                                        "Andromuks",
                                        "MediaMessage: Added encrypted=true to URL: $encryptedUrl"
                                    )
                                    encryptedUrl
                                } else {
                                    httpUrl
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

                        val blurHashPainter =
                            remember(mediaMessage.info.blurHash) {
                                mediaMessage.info.blurHash?.let { blurHash ->
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

                        if (BuildConfig.DEBUG) Log.d("Andromuks", "BlurHash painter created: ${blurHashPainter != null}")

                        if (BuildConfig.DEBUG) Log.d("Andromuks", "AsyncImage: Starting image load for $imageUrl")

                        // PERFORMANCE: Use optimized AsyncImage with better caching
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
                                .size(600, 600) // QUALITY IMPROVEMENT: Larger size for better quality
                                .build(),
                            imageLoader = imageLoader,
                            contentDescription = mediaMessage.filename,
                            modifier = Modifier
                                .then(
                                    if (shouldRenderAtActualSize) {
                                        // Render at actual size (don't enlarge)
                                        Modifier
                                            .width(calculatedWidth)
                                            .height(calculatedHeight)
                                    } else {
                                        // Scale to fit container
                                        Modifier.fillMaxSize()
                                    }
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { onImageClick() },
                                        onLongPress = {
                                            // Trigger menu on long press
                                            onImageLongPress?.invoke()
                                        }
                                    )
                                },
                            placeholder = blurHashPainter,
                            error = blurHashPainter,
                            onSuccess = {
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "✅ Image loaded successfully: $imageUrl")
                            },
                            onError = { state ->
                                if (state is coil.request.ErrorResult) {
                                    CacheUtils.handleImageLoadError(
                                        imageUrl = imageUrl ?: "",
                                        throwable = state.throwable,
                                        imageLoader = imageLoader,
                                        context = "Media"
                                    )
                                }
                            },
                            onLoading = { state ->
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "⏳ Image loading: $imageUrl, state: $state")
                            }
                        )
                    } else if (mediaMessage.msgType == "m.video") {
                        // Video thumbnail with play button overlay
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Check if video has a thumbnail
                            val thumbnailUrl = mediaMessage.info.thumbnailUrl

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
                                        Modifier.fillMaxSize().pointerInput(Unit) {
                                            detectTapGestures(
                                                onTap = { onImageClick() },
                                                onLongPress = {
                                                    // Trigger menu on long press
                                                    onImageLongPress?.invoke()
                                                }
                                            )
                                        },
                                    placeholder = thumbnailBlurHashPainter,
                                    error = thumbnailBlurHashPainter,
                                    onSuccess = {
                                        if (BuildConfig.DEBUG) Log.d(
                                            "Andromuks",
                                            "✅ Video thumbnail loaded: $thumbnailFinalUrl"
                                        )
                                    },
                                    onError = { state ->
                                        if (state is coil.request.ErrorResult) {
                                            CacheUtils.handleImageLoadError(
                                                imageUrl = thumbnailFinalUrl ?: "",
                                                throwable = state.throwable,
                                                imageLoader = imageLoader,
                                                context = "VideoThumbnail"
                                            )
                                        }
                                    }
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
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onTap = { onImageClick() },
                                                onLongPress = {
                                                    // Trigger menu on long press
                                                    onImageLongPress?.invoke()
                                                }
                                            )
                                        }
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
    
    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offsetX = (offsetX + offsetChange.x).coerceIn(-1000f, 1000f)
        offsetY = (offsetY + offsetChange.y).coerceIn(-1000f, 1000f)
    }
    
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    usePlatformDefaultWidth = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    // Reset zoom and offset on tap
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            )
                        }
                ) {
            
            // Image with zoom and pan
            val context = LocalContext.current
            
            // Use shared ImageLoader singleton with custom User-Agent
            val imageLoader = remember { ImageLoaderSingleton.get(context) }
            
            // Check if we have a cached version first
            val cachedFile = remember(mediaMessage.url) {
                MediaCache.getCachedFile(context, mediaMessage.url)
            }
            
            val imageUrl = remember(mediaMessage.url, isEncrypted, cachedFile) {
                if (cachedFile != null) {
                    // Use cached file
                    cachedFile.absolutePath
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
                contentDescription = mediaMessage.filename,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .transformable(state = transformableState)
                    .clip(RoundedCornerShape(8.dp)),
                onSuccess = { 
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "✅ ImageViewer: Image loaded successfully: $imageUrl")
                },
                onError = { state ->
                    if (state is coil.request.ErrorResult) {
                        CacheUtils.handleImageLoadError(
                            imageUrl = imageUrl,
                            throwable = state.throwable,
                            imageLoader = imageLoader,
                            context = "ImageViewer"
                        )
                    }
                }
            )
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
    
    // ExoPlayer instance
    val exoPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            // Create MediaItem with authentication headers
            val mediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(videoHttpUrl)
                .build()
            
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }
    
    Dialog(
        onDismissRequest = {
            // Stop the player before dismissing the dialog
            exoPlayer.stop()
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // Handle back button/gesture to stop video and dismiss
        BackHandler {
            exoPlayer.stop()
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
                    exoPlayer.stop()
                    exoPlayer.release()
                }
            }
            
            // Player view
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        controllerShowTimeoutMs = 3000
                        controllerHideOnTouch = true
                        
                        // Set custom request headers for authentication
                        // Note: ExoPlayer with DataSource.Factory for custom headers
                        val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setDefaultRequestProperties(mapOf("Cookie" to "gomuks_auth=$authToken"))
                        
                        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx)
                            .setDataSourceFactory(dataSourceFactory)
                        
                        // Recreate player with custom data source
                        exoPlayer.stop()
                        val newPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
                            .setMediaSourceFactory(mediaSourceFactory)
                            .build()
                            .apply {
                                val mediaItem = androidx.media3.common.MediaItem.fromUri(videoHttpUrl)
                                setMediaItem(mediaItem)
                                prepare()
                                playWhenReady = true
                            }
                        
                        player = newPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Close button overlay (top-right corner)
            IconButton(
                onClick = {
                    // Stop the player before dismissing
                    exoPlayer.stop()
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
