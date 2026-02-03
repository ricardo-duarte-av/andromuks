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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import kotlinx.coroutines.delay
import android.app.DownloadManager
import android.content.Context
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.TextView
import android.view.ViewGroup
import java.io.ByteArrayOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileOutputStream

/**
 * Shared state manager for inline video players.
 * Ensures only one video plays at a time across all timeline items.
 */
object InlineVideoPlayerManager {
    private var currentPlayingVideoId: String? = null
    private var currentPlayer: ExoPlayer? = null
    
    /**
     * Set the currently playing video. Pauses any previously playing video.
     */
    fun setCurrentVideo(videoId: String, player: ExoPlayer?) {
        // Pause previous video if different
        if (currentPlayingVideoId != null && currentPlayingVideoId != videoId && currentPlayer != null) {
            try {
                currentPlayer?.pause()
                if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineVideoPlayerManager: Paused previous video: $currentPlayingVideoId")
            } catch (e: Exception) {
                Log.e("Andromuks", "InlineVideoPlayerManager: Error pausing previous video", e)
            }
        }
        
        currentPlayingVideoId = videoId
        currentPlayer = player
        if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineVideoPlayerManager: Set current video: $videoId")
    }
    
    /**
     * Clear the current video (when player is released).
     */
    fun clearCurrentVideo(videoId: String) {
        if (currentPlayingVideoId == videoId) {
            currentPlayingVideoId = null
            currentPlayer = null
            if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineVideoPlayerManager: Cleared current video: $videoId")
        }
    }
    
    /**
     * Check if a video is currently playing.
     */
    fun isVideoPlaying(videoId: String): Boolean {
        return currentPlayingVideoId == videoId && currentPlayer?.isPlaying == true
    }
}

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
    val renderThumbnailsAlways = appViewModel?.renderThumbnailsAlways ?: true
    var showImageViewer by remember { mutableStateOf(false) }
    var showVideoPlayer by remember { mutableStateOf(false) }
    // State for inline video player
    var isVideoPlayingInline by remember { mutableStateOf(false) }
    // State for fullscreen continuation
    var fullscreenInitialPosition by remember { mutableLongStateOf(0L) }
    var fullscreenShouldAutoPlay by remember { mutableStateOf(false) }
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
            initialPosition = fullscreenInitialPosition,
            shouldAutoPlay = fullscreenShouldAutoPlay,
            onDismiss = { 
                showVideoPlayer = false
                fullscreenInitialPosition = 0L
                fullscreenShouldAutoPlay = false
            }
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
                        renderThumbnailsAlways = renderThumbnailsAlways,
                        isMine = isMine,
                        onImageClick = { 
                            if (mediaMessage.msgType == "m.video") {
                                // Toggle inline player instead of opening fullscreen
                                isVideoPlayingInline = !isVideoPlayingInline
                            } else {
                                showImageViewer = true
                            }
                        },
                        isVideoPlayingInline = isVideoPlayingInline,
                        onFullscreenRequest = { currentPosition, isPlaying ->
                            // When fullscreen is requested from inline player, close inline and open fullscreen
                            isVideoPlayingInline = false
                            fullscreenInitialPosition = currentPosition
                            fullscreenShouldAutoPlay = isPlaying
                            showVideoPlayer = true
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
                    ),
                shape = bubbleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp,  // No elevation/shadow
                shadowElevation = 0.dp  // No shadow
            ) {
                Column {
                    // Image content inside the caption bubble
                    MediaContent(
                        mediaMessage = mediaMessage,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isEncrypted = isEncrypted,
                        loadThumbnailsIfAvailable = useThumbnails,
                        renderThumbnailsAlways = renderThumbnailsAlways,
                        isMine = isMine,
                        onImageClick = { 
                            if (mediaMessage.msgType == "m.video") {
                                // Toggle inline player instead of opening fullscreen
                                isVideoPlayingInline = !isVideoPlayingInline
                            } else {
                                showImageViewer = true
                            }
                        },
                        isVideoPlayingInline = isVideoPlayingInline,
                        onFullscreenRequest = { currentPosition, isPlaying ->
                            // When fullscreen is requested from inline player, close inline and open fullscreen
                            isVideoPlayingInline = false
                            fullscreenInitialPosition = currentPosition
                            fullscreenShouldAutoPlay = isPlaying
                            showVideoPlayer = true
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
                        renderThumbnailsAlways = renderThumbnailsAlways,
                        isMine = isMine,
                        onImageClick = { 
                            if (mediaMessage.msgType == "m.video") {
                                // Toggle inline player instead of opening fullscreen
                                isVideoPlayingInline = !isVideoPlayingInline
                            } else {
                                showImageViewer = true
                            }
                        },
                        isVideoPlayingInline = isVideoPlayingInline,
                        onFullscreenRequest = { currentPosition, isPlaying ->
                            // When fullscreen is requested from inline player, close inline and open fullscreen
                            isVideoPlayingInline = false
                            fullscreenInitialPosition = currentPosition
                            fullscreenShouldAutoPlay = isPlaying
                            showVideoPlayer = true
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
                    .wrapContentHeight(                    ),
                shape = bubbleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp,  // No elevation/shadow
                shadowElevation = 0.dp  // No shadow
            ) {
                Column {
                    MediaContent(
                        mediaMessage = mediaMessage,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isEncrypted = isEncrypted,
                        loadThumbnailsIfAvailable = useThumbnails,
                        renderThumbnailsAlways = renderThumbnailsAlways,
                        isMine = isMine,
                        onImageClick = { 
                            if (mediaMessage.msgType == "m.video") {
                                // Toggle inline player instead of opening fullscreen
                                isVideoPlayingInline = !isVideoPlayingInline
                            } else {
                                showImageViewer = true
                            }
                        },
                        isVideoPlayingInline = isVideoPlayingInline,
                        onFullscreenRequest = { currentPosition, isPlaying ->
                            // When fullscreen is requested from inline player, close inline and open fullscreen
                            isVideoPlayingInline = false
                            fullscreenInitialPosition = currentPosition
                            fullscreenShouldAutoPlay = isPlaying
                            showVideoPlayer = true
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
    renderThumbnailsAlways: Boolean = true,
    isMine: Boolean = false, // For determining bubble shape
    onImageClick: () -> Unit = {},
    onImageLongPress: (() -> Unit)? = null,
    isVideoPlayingInline: Boolean = false, // For inline video player
    onFullscreenRequest: (currentPosition: Long, isPlaying: Boolean) -> Unit = { _, _ -> } // Callback for fullscreen request from inline player
) {
    val density = LocalDensity.current
    
    // Determine if we're using thumbnails
    // MSC4230: Skip thumbnails for animated images (GIF, animated PNG, animated WebP) to ensure animation plays
    // Thumbnails for animated images are typically static JPG/PNG, so we need to use the original
    val useThumbnail = loadThumbnailsIfAvailable && 
                       mediaMessage.info.thumbnailUrl != null &&
                       mediaMessage.info.isAnimated != true  // Skip thumbnail for animated images
    
    // State to track if user has tapped to reveal thumbnail (only relevant when renderThumbnailsAlways is false)
    var isRevealed by remember(mediaMessage.url) { mutableStateOf(false) }
    
    // Determine if we should show blurhash placeholder
    val shouldShowPlaceholder = !renderThumbnailsAlways && !isRevealed && 
                                (mediaMessage.msgType == "m.image" || mediaMessage.msgType == "m.video")
    
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

                        // Calculate blurhash dimensions based on thumbnail or original image dimensions
                        // Use a reasonable size (100px on smaller dimension) while maintaining aspect ratio
                        val blurHashWidth: Int
                        val blurHashHeight: Int
                        if (useThumbnail && mediaMessage.info.thumbnailWidth != null && mediaMessage.info.thumbnailHeight != null &&
                            mediaMessage.info.thumbnailWidth!! > 0 && mediaMessage.info.thumbnailHeight!! > 0) {
                            // Use thumbnail dimensions
                            val thumbW = mediaMessage.info.thumbnailWidth!!
                            val thumbH = mediaMessage.info.thumbnailHeight!!
                            val scale = 100f / minOf(thumbW, thumbH).toFloat()
                            blurHashWidth = (thumbW * scale).toInt().coerceAtLeast(32)
                            blurHashHeight = (thumbH * scale).toInt().coerceAtLeast(32)
                        } else if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
                            // Use original image dimensions
                            val scale = 100f / minOf(mediaMessage.info.width, mediaMessage.info.height).toFloat()
                            blurHashWidth = (mediaMessage.info.width * scale).toInt().coerceAtLeast(32)
                            blurHashHeight = (mediaMessage.info.height * scale).toInt().coerceAtLeast(32)
                        } else {
                            // Fallback to square if dimensions unknown
                            blurHashWidth = 32
                            blurHashHeight = 32
                        }

                        val blurHashPainter =
                            remember(blurHashForDisplay, blurHashWidth, blurHashHeight) {
                                blurHashForDisplay?.let { blurHash ->
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "Decoding BlurHash: $blurHash to ${blurHashWidth}x${blurHashHeight}")
                                    val bitmap = BlurHashUtils.decodeBlurHash(blurHash, blurHashWidth, blurHashHeight)
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
                                                blurHashWidth,
                                                blurHashHeight,
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
                                                blurHashWidth,
                                                blurHashHeight,
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

                        if (shouldShowPlaceholder) {
                            // Show blurhash placeholder with "Tap to show" text
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(loadedAspectRatio)
                                    .scale(1.02f)
                                    .clickable {
                                        // First tap: reveal thumbnail
                                        isRevealed = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // Show blurhash or empty box
                                if (blurHashForDisplay != null) {
                                    Image(
                                        painter = blurHashPainter,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    // Empty box with background
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )
                                }
                                
                                // "Tap to show" text overlay
                                Text(
                                    text = "Tap to show",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        } else {
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
                        }
                    } else if (mediaMessage.msgType == "m.video") {
                        // Check if we should show inline player or thumbnail
                        if (isVideoPlayingInline) {
                            // Show inline video player
                            InlineVideoPlayer(
                                mediaMessage = mediaMessage,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                isEncrypted = isEncrypted,
                                aspectRatio = aspectRatio,
                                videoId = mediaMessage.url, // Use MXC URL as unique ID
                                onFullscreenClick = { currentPosition, isPlaying ->
                                    // Switch to fullscreen player with current position and playing state
                                    onFullscreenRequest(currentPosition, isPlaying)
                                },
                                modifier = Modifier
                            )
                        } else {
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
                            
                            // Get blurhash for video
                            val videoBlurHash = mediaMessage.info.thumbnailBlurHash ?: mediaMessage.info.blurHash
                            
                            // Calculate blurhash dimensions based on thumbnail or original video dimensions
                            // Use a reasonable size (100px on smaller dimension) while maintaining aspect ratio
                            val videoBlurHashWidth: Int
                            val videoBlurHashHeight: Int
                            if (mediaMessage.info.thumbnailWidth != null && mediaMessage.info.thumbnailHeight != null &&
                                mediaMessage.info.thumbnailWidth!! > 0 && mediaMessage.info.thumbnailHeight!! > 0) {
                                // Use thumbnail dimensions
                                val thumbW = mediaMessage.info.thumbnailWidth!!
                                val thumbH = mediaMessage.info.thumbnailHeight!!
                                val scale = 100f / minOf(thumbW, thumbH).toFloat()
                                videoBlurHashWidth = (thumbW * scale).toInt().coerceAtLeast(32)
                                videoBlurHashHeight = (thumbH * scale).toInt().coerceAtLeast(32)
                            } else if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
                                // Use original video dimensions
                                val scale = 100f / minOf(mediaMessage.info.width, mediaMessage.info.height).toFloat()
                                videoBlurHashWidth = (mediaMessage.info.width * scale).toInt().coerceAtLeast(32)
                                videoBlurHashHeight = (mediaMessage.info.height * scale).toInt().coerceAtLeast(32)
                            } else {
                                // Fallback to square if dimensions unknown
                                videoBlurHashWidth = 32
                                videoBlurHashHeight = 32
                            }
                            
                            val videoBlurHashPainter = remember(videoBlurHash, videoBlurHashWidth, videoBlurHashHeight) {
                                videoBlurHash?.let { blurHash ->
                                    val bitmap = BlurHashUtils.decodeBlurHash(blurHash, videoBlurHashWidth, videoBlurHashHeight)
                                    if (bitmap != null) {
                                        BitmapPainter(bitmap.asImageBitmap())
                                    } else {
                                        BitmapPainter(
                                            BlurHashUtils.createPlaceholderBitmap(
                                                videoBlurHashWidth,
                                                videoBlurHashHeight,
                                                androidx.compose.ui.graphics.Color.Gray
                                            )
                                        )
                                    }
                                }
                                    ?: BitmapPainter(
                                        BlurHashUtils.createPlaceholderBitmap(
                                            videoBlurHashWidth,
                                            videoBlurHashHeight,
                                            androidx.compose.ui.graphics.Color.Gray
                                        )
                                    )
                            }
                            
                            Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(loadedThumbnailAspectRatio)
                                .scale(1.02f), // Make thumbnail slightly larger than frame so it gets clipped
                            contentAlignment = Alignment.Center
                        ) {
                            if (shouldShowPlaceholder) {
                                // Show blurhash placeholder with "Tap to show" text
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            // First tap: reveal thumbnail
                                            isRevealed = true
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Show blurhash or empty box
                                    if (videoBlurHash != null) {
                                        Image(
                                            painter = videoBlurHashPainter,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    } else {
                                        // Empty box with background
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                    }
                                    
                                    // "Tap to show" text overlay
                                    Text(
                                        text = "Tap to show",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            } else if (thumbnailUrl != null) {
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
                                // Fallback: No thumbnail available
                                if (shouldShowPlaceholder) {
                                    // Show blurhash placeholder with "Tap to show" text
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable {
                                                // First tap: for videos without thumbnails, open viewer directly
                                                onImageClick()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // Show blurhash or empty box
                                        if (videoBlurHash != null) {
                                            Image(
                                                painter = videoBlurHashPainter,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                                            )
                                        } else {
                                            // Empty box with background
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                            )
                                        }
                                        
                                        // "Tap to show" text overlay
                                        Text(
                                            text = "Tap to show",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                } else {
                                    // No thumbnail available, show placeholder
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
    }
}

/**
 * Inline video player composable that replaces thumbnail when playing.
 * Features:
 * - ExoPlayer embedded in timeline
 * - Play/pause and fullscreen buttons
 * - Auto-hide controls after 3 seconds
 * - Proper lifecycle management
 */
@Composable
private fun InlineVideoPlayer(
    mediaMessage: MediaMessage,
    homeserverUrl: String,
    authToken: String,
    isEncrypted: Boolean,
    aspectRatio: Float,
    videoId: String,
    onFullscreenClick: (currentPosition: Long, isPlaying: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val primaryColor = colorScheme.primary
    
    // Player state
    var player by remember(videoId) { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    
    // Auto-hide controls after 3 seconds
    LaunchedEffect(isPlaying, showControls) {
        if (isPlaying && showControls) {
            kotlinx.coroutines.delay(3000)
            if (isPlaying) {
                controlsVisible = false
            }
        } else if (!isPlaying) {
            controlsVisible = true
        }
    }
    
    // Reset controls visibility when user interacts
    LaunchedEffect(showControls) {
        if (showControls) {
            controlsVisible = true
        }
    }
    
    // Convert MXC URL to HTTP URL
    val videoHttpUrl = remember(mediaMessage.url, isEncrypted) {
        val httpUrl = MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
        if (isEncrypted && httpUrl != null) {
            "$httpUrl?encrypted=true"
        } else {
            httpUrl ?: ""
        }
    }
    
    // Create and manage ExoPlayer
    DisposableEffect(videoId, videoHttpUrl) {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineVideoPlayer: Creating player for $videoId")
        
        val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Cookie" to "gomuks_auth=$authToken"))
        
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
        
        val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
            }
        
        // Set media item
        if (videoHttpUrl.isNotEmpty()) {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(videoHttpUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            // Auto-play when inline player is first shown
            exoPlayer.play()
            InlineVideoPlayerManager.setCurrentVideo(videoId, exoPlayer)
        }
        
        // Register listener
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlayingValue: Boolean) {
                isPlaying = isPlayingValue
                InlineVideoPlayerManager.setCurrentVideo(videoId, if (isPlayingValue) exoPlayer else null)
            }
        }
        exoPlayer.addListener(listener)
        
        player = exoPlayer
        
        onDispose {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "InlineVideoPlayer: Releasing player for $videoId")
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
            InlineVideoPlayerManager.clearCurrentVideo(videoId)
            player = null
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .scale(1.02f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Toggle play/pause on tap
                        player?.let { p ->
                            if (p.isPlaying) {
                                p.pause()
                            } else {
                                p.play()
                                InlineVideoPlayerManager.setCurrentVideo(videoId, p)
                            }
                            showControls = true
                            controlsVisible = true
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // ExoPlayer view
        player?.let { p ->
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = p
                        useController = false // We'll use custom controls
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.player = p
                }
            )
        }
        
        // Controls overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Show controls on tap
                                showControls = true
                                controlsVisible = true
                            }
                        )
                    }
            ) {
                // Play/Pause button (center)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    player?.let { p ->
                                        if (p.isPlaying) {
                                            p.pause()
                                        } else {
                                            p.play()
                                            InlineVideoPlayerManager.setCurrentVideo(videoId, p)
                                        }
                                        showControls = true
                                        controlsVisible = true
                                    }
                                }
                            )
                        }
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        IconButton(
                            onClick = {
                                player?.let { p ->
                                    if (p.isPlaying) {
                                        p.pause()
                                    } else {
                                        p.play()
                                        InlineVideoPlayerManager.setCurrentVideo(videoId, p)
                                    }
                                    showControls = true
                                    controlsVisible = true
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                
                // Fullscreen button (top-right)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        IconButton(
                            onClick = {
                                player?.let { p ->
                                    onFullscreenClick(p.currentPosition, p.isPlaying)
                                } ?: run {
                                    onFullscreenClick(0L, false)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
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
 * Save video to device gallery using MediaStore
 */
private suspend fun saveVideoToGallery(
    context: Context,
    videoUrl: String,
    filename: String?,
    mimeType: String,
    authToken: String
) = withContext(Dispatchers.IO) {
    try {
        // Determine filename and extension from MIME type
        val extension = when {
            mimeType.contains("webm") -> "webm"
            mimeType.contains("quicktime") || mimeType.contains("mov") -> "mov"
            mimeType.contains("avi") -> "avi"
            mimeType.contains("mkv") -> "mkv"
            else -> "mp4" // Default to mp4
        }
        
        val displayName = filename?.takeIf { it.isNotBlank() } 
            ?: "video_${System.currentTimeMillis()}.$extension"
        val finalFilename = if (!displayName.contains(".")) {
            "$displayName.$extension"
        } else {
            displayName
        }
        
        // Download video using OkHttp
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(videoUrl)
            .addHeader("Cookie", "gomuks_auth=$authToken")
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Failed to download video: ${response.code}")
        }
        
        // Save to MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, finalFilename)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Andromuks")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        
        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw Exception("Failed to create MediaStore entry")
        
        // Write video data
        context.contentResolver.openOutputStream(uri)?.use { output ->
            response.body?.byteStream()?.use { input ->
                input.copyTo(output)
            }
        }
        
        // Mark as not pending (Android Q+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }
        
        // Show success toast
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                "Video saved to gallery",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        
        if (BuildConfig.DEBUG) Log.d("Andromuks", "Video saved to gallery: $finalFilename")
    } catch (e: Exception) {
        Log.e("Andromuks", "Failed to save video to gallery", e)
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                "Failed to save video: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
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
    initialPosition: Long = 0L,
    shouldAutoPlay: Boolean = false,
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
    
    // Track player state for progress bar
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableStateOf(androidx.media3.common.Player.STATE_IDLE) }
    
    val coroutineScope = rememberCoroutineScope()
    
    // Get Material 3 colors for ExoPlayer controls
    val colorScheme = MaterialTheme.colorScheme
    val primaryColor = colorScheme.primary
    val primaryColorInt = AndroidColor.valueOf(
        primaryColor.red,
        primaryColor.green,
        primaryColor.blue,
        primaryColor.alpha
    ).toArgb()
    
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
            // Use Unit as key so it only runs on actual dispose, not when actualPlayer changes
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    actualPlayer?.let { player ->
                        player.stop()
                        player.release()
                    }
                }
            }
            
            // Update position and state periodically using LaunchedEffect
            LaunchedEffect(actualPlayer) {
                while (actualPlayer != null) {
                    kotlinx.coroutines.delay(100)
                    actualPlayer?.let { player ->
                        currentPosition = player.currentPosition
                        isPlaying = player.isPlaying
                        playbackState = player.playbackState
                        if (duration == 0L && player.duration > 0) {
                            duration = player.duration
                        }
                    }
                }
            }
            
            // Player view - use key to prevent recreation
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Creating PlayerView")
                        // Set custom request headers for authentication
                        val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setDefaultRequestProperties(mapOf("Cookie" to "gomuks_auth=$authToken"))
                        
                        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx)
                            .setDataSourceFactory(dataSourceFactory)
                        
                    // Create player with custom data source factory
                    val player = androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
                            .setMediaSourceFactory(mediaSourceFactory)
                            .build()
                    
                    // Store reference to the actual player FIRST
                    actualPlayer = player
                    
                    // Track if we've already performed the initial seek and resume
                    var hasSeekedToInitialPosition = false
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    
                    // Listen to player state changes
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onIsPlayingChanged(isPlayingValue: Boolean) {
                            isPlaying = isPlayingValue
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            playbackState = state
                            if (state == androidx.media3.common.Player.STATE_READY) {
                                duration = player.duration
                                // Seek to initial position when ready (if provided and not already seeked)
                                if (initialPosition > 0L && !hasSeekedToInitialPosition) {
                                    hasSeekedToInitialPosition = true
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Seeking to position $initialPosition, shouldAutoPlay=$shouldAutoPlay")
                                    // Set playWhenReady BEFORE seeking, so playback resumes automatically after seek completes
                                    if (shouldAutoPlay) {
                                        player.playWhenReady = true
                                    }
                                    player.seekTo(initialPosition)
                                    // Also explicitly call play() to ensure playback starts
                                    if (shouldAutoPlay) {
                                        handler.postDelayed({
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Ensuring playback after seek, currentPosition=${player.currentPosition}, state=${player.playbackState}, isPlaying=${player.isPlaying}")
                                            if (!player.isPlaying && player.playbackState == androidx.media3.common.Player.STATE_READY) {
                                                player.play()
                                            }
                                        }, 200)
                                    }
                                }
                            }
                        }
                        override fun onPositionDiscontinuity(
                            oldPosition: androidx.media3.common.Player.PositionInfo,
                            newPosition: androidx.media3.common.Player.PositionInfo,
                            reason: Int
                        ) {
                            currentPosition = player.currentPosition
                            // If we just seeked and should be playing, ensure playback resumes
                            if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK && 
                                shouldAutoPlay && 
                                hasSeekedToInitialPosition &&
                                !player.isPlaying &&
                                player.playbackState == androidx.media3.common.Player.STATE_READY) {
                                handler.post {
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Resuming playback after seek discontinuity, position=${player.currentPosition}")
                                    player.playWhenReady = true
                                    player.play()
                                }
                            }
                        }
                    }
                    player.addListener(listener)
                    
                    androidx.media3.ui.PlayerView(ctx).apply {
                        // Set player FIRST before preparing media
                        this.player = player
                        
                        // Set media item and prepare AFTER player is attached to view
                        // Only proceed if we have a valid video URL
                        if (videoHttpUrl.isNotEmpty()) {
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Setting up video: $videoHttpUrl, initialPosition=$initialPosition, shouldAutoPlay=$shouldAutoPlay")
                                val mediaItem = androidx.media3.common.MediaItem.fromUri(videoHttpUrl)
                            player.setMediaItem(mediaItem)
                            player.prepare()
                            // Set playWhenReady based on shouldAutoPlay
                            // If we need to seek, we'll set playWhenReady=true in the listener before seeking
                            // so playback resumes automatically after seek completes
                            if (initialPosition == 0L) {
                                // No seek needed, just set playWhenReady
                                player.playWhenReady = shouldAutoPlay
                            } else {
                                // We'll set playWhenReady=true in the listener before seeking
                                // For now, set it to false to prevent premature playback
                                player.playWhenReady = false
                            }
                        } else {
                            if (BuildConfig.DEBUG) Log.e("Andromuks", "VideoPlayerDialog: Empty video URL!")
                        }
                        useController = true
                        controllerShowTimeoutMs = 3000
                        controllerHideOnTouch = true
                        // Hide fullscreen button (the X button in top right)
                        // Note: setShowFullscreenButton might not be available in all ExoPlayer versions
                        // We'll hide it via ViewTreeObserver instead
                        
                        // Hide default progress bar - we'll use Compose wavy progress bar instead
                        val progressBar = findViewById<androidx.media3.ui.DefaultTimeBar>(
                            androidx.media3.ui.R.id.exo_progress
                        )
                        progressBar?.visibility = android.view.View.GONE
                        
                        // Function to hide close buttons
                        fun hideCloseButtons(v: android.view.View) {
                            if (v is android.widget.ImageButton) {
                                val contentDesc = v.contentDescription?.toString()?.lowercase() ?: ""
                                val tag = v.tag?.toString()?.lowercase() ?: ""
                                val idName = try {
                                    v.resources.getResourceEntryName(v.id).lowercase()
                                } catch (e: Exception) {
                                    ""
                                }
                                // Hide if it's a close/fullscreen button or if it's in the top-right corner
                                val isTopRight = try {
                                    val location = IntArray(2)
                                    v.getLocationOnScreen(location)
                                    val screenWidth = v.resources.displayMetrics.widthPixels
                                    location[0] > screenWidth * 0.7f && location[1] < screenWidth * 0.2f
                                } catch (e: Exception) {
                                    false
                                }
                                if (contentDesc.contains("close") || contentDesc.contains("fullscreen") || 
                                    tag.contains("close") || tag.contains("fullscreen") ||
                                    idName.contains("close") || idName.contains("fullscreen") ||
                                    isTopRight) {
                                    v.visibility = android.view.View.GONE
                                }
                            }
                            if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) {
                                    hideCloseButtons(v.getChildAt(i))
                                }
                            }
                        }
                        
                        // Function to hide rewind/fast forward and prev/next buttons
                        fun hideRewindFastForwardButtons() {
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: hideRewindFastForwardButtons called")
                            val controllerView = findViewById<ViewGroup>(
                                androidx.media3.ui.R.id.exo_controller
                            )
                            if (controllerView == null) {
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: controllerView is null")
                                return
                            }
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: controllerView found, childCount=${controllerView.childCount}")
                            
                            // Log ALL views (not just ImageButtons) in controller for debugging
                            fun logAllViews(v: android.view.View, depth: Int = 0) {
                                val idName = try {
                                    v.resources.getResourceEntryName(v.id).lowercase()
                                } catch (e: Exception) {
                                    "unknown"
                                }
                                val contentDesc = v.contentDescription?.toString() ?: ""
                                val visibility = when (v.visibility) {
                                    android.view.View.VISIBLE -> "VISIBLE"
                                    android.view.View.GONE -> "GONE"
                                    android.view.View.INVISIBLE -> "INVISIBLE"
                                    else -> "OTHER"
                                    }
                                val viewType = v.javaClass.simpleName
                                // Log views that might be buttons or contain "rew"/"ffwd" in their ID
                                if (v is ImageButton || v is android.widget.Button || 
                                    idName.contains("rew") || idName.contains("ffwd") || 
                                    idName.contains("rewind") || idName.contains("fastforward") ||
                                    contentDesc.contains("rewind") || contentDesc.contains("fast forward") ||
                                    contentDesc.contains("backward") || contentDesc.contains("forward")) {
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Found view: type=$viewType, id=$idName, desc=$contentDesc, visibility=$visibility, depth=$depth")
                                }
                                if (v is android.view.ViewGroup) {
                                    for (i in 0 until v.childCount) {
                                        logAllViews(v.getChildAt(i), depth + 1)
                                    }
                                }
                            }
                            if (BuildConfig.DEBUG) logAllViews(controllerView)
                            
                            // Also search for ANY view type that might be rewind/fast forward/prev/next
                            fun findAndHideAnyUnwantedButtons(v: android.view.View) {
                                val idName = try {
                                    v.resources.getResourceEntryName(v.id).lowercase()
                                } catch (e: Exception) {
                                    ""
                                }
                                val contentDesc = v.contentDescription?.toString()?.lowercase() ?: ""
                                // Check for rewind, fast forward, prev, or next in ANY view type
                                if (idName.contains("rew") || idName.contains("rewind") ||
                                    contentDesc.contains("rewind") || contentDesc.contains("backward") ||
                                    idName == "exo_rew" || idName == "exo_rew_with_amount" ||
                                    idName.contains("ffwd") || idName.contains("fastforward") || idName.contains("fast_forward") ||
                                    contentDesc.contains("fast forward") || contentDesc.contains("forward") ||
                                    idName == "exo_ffwd" || idName == "exo_ffwd_with_amount" ||
                                    idName == "exo_prev" || idName.contains("prev") ||
                                    contentDesc.contains("previous") || contentDesc.contains("anterior") ||
                                    idName == "exo_next" || idName.contains("next") ||
                                    contentDesc.contains("next") || contentDesc.contains("seguinte")) {
                                    v.visibility = android.view.View.GONE
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Hid view (any type): type=${v.javaClass.simpleName}, id=$idName, desc=$contentDesc")
                                }
                                if (v is android.view.ViewGroup) {
                                    for (i in 0 until v.childCount) {
                                        findAndHideAnyUnwantedButtons(v.getChildAt(i))
                                    }
                                }
                            }
                            findAndHideAnyUnwantedButtons(controllerView)
                            findAndHideAnyUnwantedButtons(this@apply)
                            
                            // Also try direct find for prev/next
                            val prevButton = controllerView.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_prev
                            )
                            if (prevButton != null) {
                                prevButton.visibility = android.view.View.GONE
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Hid prev button (direct find)")
                            }
                            
                            val nextButton = controllerView.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_next
                            )
                            if (nextButton != null) {
                                nextButton.visibility = android.view.View.GONE
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Hid next button (direct find)")
                            }
                            
                            // Helper function to find and hide rewind/fast forward buttons (check all, not just visible)
                            fun findAndHideRewindFastForward(v: android.view.View) {
                                if (v is ImageButton) {
                                    val idName = try {
                                        v.resources.getResourceEntryName(v.id).lowercase()
                                    } catch (e: Exception) {
                                        ""
                                    }
                                    val contentDesc = v.contentDescription?.toString()?.lowercase() ?: ""
                                    // Check for rewind or fast forward
                                    if (idName.contains("rew") || idName.contains("rewind") ||
                                        contentDesc.contains("rewind") || contentDesc.contains("backward") ||
                                        idName == "exo_rew" ||
                                        idName.contains("ffwd") || idName.contains("fastforward") || idName.contains("fast_forward") ||
                                        contentDesc.contains("fast forward") || contentDesc.contains("forward") ||
                                        idName == "exo_ffwd") {
                                        v.visibility = android.view.View.GONE
                                        if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Hid button (id=$idName, desc=$contentDesc)")
                                    }
                                }
                                if (v is android.view.ViewGroup) {
                                    for (i in 0 until v.childCount) {
                                        findAndHideRewindFastForward(v.getChildAt(i))
                                    }
                                }
                            }
                            
                            // Try direct find first
                            val rewindButton = controllerView.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_rew
                            )
                            if (rewindButton != null) {
                                rewindButton.visibility = android.view.View.GONE
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Hid rewind button (direct find)")
                            }
                            
                            val fastForwardButton = controllerView.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_ffwd
                            )
                            if (fastForwardButton != null) {
                                fastForwardButton.visibility = android.view.View.GONE
                                if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Hid fast forward button (direct find)")
                            }
                            
                            // Search recursively through entire controller and PlayerView
                            findAndHideRewindFastForward(controllerView)
                            findAndHideRewindFastForward(this@apply)
                        }
                        
                        // Use ViewTreeObserver to continuously monitor and hide buttons
                        val observer = viewTreeObserver
                        val layoutListener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                // Hide rewind/fast forward buttons
                                hideRewindFastForwardButtons()
                                
                                // Hide all ImageButtons in overlay (where fullscreen button typically is)
                                val overlay = findViewById<android.view.ViewGroup>(
                                    androidx.media3.ui.R.id.exo_overlay
                                )
                                overlay?.let { overlayGroup ->
                                    for (i in 0 until overlayGroup.childCount) {
                                        val child = overlayGroup.getChildAt(i)
                                        if (child is android.widget.ImageButton) {
                                            child.visibility = android.view.View.GONE
                                        }
                                    }
                                }
                                
                                // Recursively search and hide any top-right buttons
                                val rootView = rootView
                                if (rootView != null) {
                                    val screenWidth = resources.displayMetrics.widthPixels
                                    val screenHeight = resources.displayMetrics.heightPixels
                                    fun findAndHideTopRightButtons(v: android.view.View) {
                                        if (v is android.widget.ImageButton && v.visibility == android.view.View.VISIBLE) {
                                            try {
                                                val location = IntArray(2)
                                                v.getLocationOnScreen(location)
                                                // Check if button is in top-right area (last 15% width, first 20% height)
                                                if (location[0] > screenWidth * 0.85f && location[1] < screenHeight * 0.2f) {
                                                    v.visibility = android.view.View.GONE
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Hid top-right button at (${location[0]}, ${location[1]})")
                                                }
                                            } catch (e: Exception) {
                                                // Button might not be laid out yet, try hiding by ID/description
                                                val idName = try {
                                                    v.resources.getResourceEntryName(v.id).lowercase()
                                                } catch (e2: Exception) {
                                                    ""
                                                }
                                                val contentDesc = v.contentDescription?.toString()?.lowercase() ?: ""
                                                if (idName.contains("close") || idName.contains("fullscreen") ||
                                                    contentDesc.contains("close") || contentDesc.contains("fullscreen")) {
                                                    v.visibility = android.view.View.GONE
                                                }
                                            }
                                        }
                                        if (v is android.view.ViewGroup) {
                                            for (i in 0 until v.childCount) {
                                                findAndHideTopRightButtons(v.getChildAt(i))
                                            }
                                        }
                                    }
                                    findAndHideTopRightButtons(rootView)
                                }
                                
                                // Also use the recursive hideCloseButtons function
                                hideCloseButtons(this@apply)
                            }
                        }
                        observer.addOnGlobalLayoutListener(layoutListener)
                        
                        // Post to ensure view is laid out before customizing controls
                        post {
                            hideCloseButtons(this)
                        }
                        
                        // Function to theme buttons and hide rewind/fast forward
                        fun themeAllButtons() {
                            // Find and customize control views
                            val controllerView = findViewById<ViewGroup>(
                                androidx.media3.ui.R.id.exo_controller
                            )
                            controllerView?.let { controller ->
                                // Customize play/pause button
                                val playButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_play_pause
                                )
                                playButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                }
                                
                                // Customize previous button
                                val prevButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_prev
                                )
                                prevButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                }
                                
                                // Customize next button
                                val nextButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_next
                                )
                                nextButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                }
                                
                                // Customize rewind button (minus 5 seconds) - search more thoroughly
                                var rewindButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_rew
                                )
                                // Also try finding by traversing all children recursively
                                if (rewindButton == null) {
                                    fun findRewindButton(v: android.view.View): ImageButton? {
                                        if (v is ImageButton) {
                                            val idName = try {
                                                v.resources.getResourceEntryName(v.id).lowercase()
                                            } catch (e: Exception) {
                                                ""
                                            }
                                            val contentDesc = v.contentDescription?.toString()?.lowercase() ?: ""
                                            if (idName.contains("rew") || idName.contains("rewind") ||
                                                contentDesc.contains("rewind") || contentDesc.contains("backward")) {
                                                return v
                                            }
                                        }
                                        if (v is android.view.ViewGroup) {
                                            for (i in 0 until v.childCount) {
                                                val found = findRewindButton(v.getChildAt(i))
                                                if (found != null) return found
                                            }
                                        }
                                        return null
                                    }
                                    rewindButton = findRewindButton(controller)
                                }
                                rewindButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Themed rewind button")
                                }
                                
                                // Customize fast forward button (plus 15 seconds) - search more thoroughly
                                var fastForwardButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_ffwd
                                )
                                // Also try finding by traversing all children recursively
                                if (fastForwardButton == null) {
                                    fun findFastForwardButton(v: android.view.View): ImageButton? {
                                        if (v is ImageButton) {
                                            val idName = try {
                                                v.resources.getResourceEntryName(v.id).lowercase()
                                            } catch (e: Exception) {
                                                ""
                                            }
                                            val contentDesc = v.contentDescription?.toString()?.lowercase() ?: ""
                                            if (idName.contains("ffwd") || idName.contains("fastforward") || idName.contains("fast_forward") ||
                                                contentDesc.contains("fast forward") || contentDesc.contains("forward")) {
                                                return v
                                            }
                                        }
                                        if (v is android.view.ViewGroup) {
                                            for (i in 0 until v.childCount) {
                                                val found = findFastForwardButton(v.getChildAt(i))
                                                if (found != null) return found
                                            }
                                        }
                                        return null
                                    }
                                    fastForwardButton = findFastForwardButton(controller)
                                }
                                fastForwardButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Themed fast forward button")
                                }
                                
                                // Customize settings/overflow menu button (bottom right)
                                val settingsButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_settings
                                ) ?: controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_overflow_show
                                )
                                settingsButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                }
                                
                                // Customize time text views
                                val currentTime = controller.findViewById<TextView>(
                                    androidx.media3.ui.R.id.exo_position
                                )
                                currentTime?.setTextColor(primaryColorInt)
                                
                                val totalTime = controller.findViewById<TextView>(
                                    androidx.media3.ui.R.id.exo_duration
                                )
                                totalTime?.setTextColor(primaryColorInt)
                            }
                        }
                        
                        // Theme buttons immediately, then again after delays to catch late-created buttons
                        themeAllButtons()
                        post {
                            themeAllButtons()
                        }
                        
                        // Use ViewTreeObserver to theme buttons whenever layout changes (catches late-created buttons)
                        viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                themeAllButtons()
                            }
                        })
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            themeAllButtons()
                        }, 100)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            themeAllButtons()
                        }, 500)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            themeAllButtons()
                        }, 1000)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            // Find and customize control views
                            val controllerView = findViewById<ViewGroup>(
                                androidx.media3.ui.R.id.exo_controller
                            )
                            controllerView?.let { controller ->
                                // Customize play/pause button
                                val playButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_play_pause
                                )
                                playButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                }
                                
                                // Customize previous button
                                val prevButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_prev
                                )
                                prevButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                }
                                
                                // Customize next button
                                val nextButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_next
                                )
                                nextButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                }
                                
                                // Customize rewind button (minus 5 seconds) - search more thoroughly
                                var rewindButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_rew
                                )
                                // Also try finding by traversing all children recursively
                                if (rewindButton == null) {
                                    fun findRewindButton(v: android.view.View): ImageButton? {
                                        if (v is ImageButton) {
                                            val idName = try {
                                                v.resources.getResourceEntryName(v.id).lowercase()
                                            } catch (e: Exception) {
                                                ""
                                            }
                                            val contentDesc = v.contentDescription?.toString()?.lowercase() ?: ""
                                            if (idName.contains("rew") || idName.contains("rewind") ||
                                                contentDesc.contains("rewind") || contentDesc.contains("backward")) {
                                                return v
                                            }
                                        }
                                        if (v is android.view.ViewGroup) {
                                            for (i in 0 until v.childCount) {
                                                val found = findRewindButton(v.getChildAt(i))
                                                if (found != null) return found
                                            }
                                        }
                                        return null
                                    }
                                    rewindButton = findRewindButton(controller)
                                }
                                rewindButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Themed rewind button")
                                }
                                
                                // Customize fast forward button (plus 15 seconds) - search more thoroughly
                                var fastForwardButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_ffwd
                                )
                                // Also try finding by traversing all children recursively
                                if (fastForwardButton == null) {
                                    fun findFastForwardButton(v: android.view.View): ImageButton? {
                                        if (v is ImageButton) {
                                            val idName = try {
                                                v.resources.getResourceEntryName(v.id).lowercase()
                                            } catch (e: Exception) {
                                                ""
                                            }
                                            val contentDesc = v.contentDescription?.toString()?.lowercase() ?: ""
                                            if (idName.contains("ffwd") || idName.contains("fastforward") || idName.contains("fast_forward") ||
                                                contentDesc.contains("fast forward") || contentDesc.contains("forward")) {
                                                return v
                                            }
                                        }
                                        if (v is android.view.ViewGroup) {
                                            for (i in 0 until v.childCount) {
                                                val found = findFastForwardButton(v.getChildAt(i))
                                                if (found != null) return found
                                            }
                                        }
                                        return null
                                    }
                                    fastForwardButton = findFastForwardButton(controller)
                                }
                                fastForwardButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Themed fast forward button")
                                }
                                
                                // Customize settings/overflow menu button (bottom right)
                                val settingsButton = controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_settings
                                ) ?: controller.findViewById<ImageButton>(
                                    androidx.media3.ui.R.id.exo_overflow_show
                                )
                                settingsButton?.let {
                                    it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                                }
                                
                                // Customize time text views
                                val currentTime = controller.findViewById<TextView>(
                                    androidx.media3.ui.R.id.exo_position
                                )
                                currentTime?.setTextColor(primaryColorInt)
                                
                                val totalTime = controller.findViewById<TextView>(
                                    androidx.media3.ui.R.id.exo_duration
                                )
                                totalTime?.setTextColor(primaryColorInt)
                            }
                        }, 200)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // Update colors when theme changes
                    val primaryColorInt = AndroidColor.valueOf(
                        primaryColor.red,
                        primaryColor.green,
                        primaryColor.blue,
                        primaryColor.alpha
                    ).toArgb()
                    
                    view.post {
                        // Hide ExoPlayer's default progress bar in update block too
                        val progressBar = view.findViewById<androidx.media3.ui.DefaultTimeBar>(
                            androidx.media3.ui.R.id.exo_progress
                        )
                        progressBar?.visibility = android.view.View.GONE
                        
                        // Hide any overlay buttons (like fullscreen/close) that might appear
                        val overlay = view.findViewById<android.view.ViewGroup>(
                            androidx.media3.ui.R.id.exo_overlay
                        )
                        overlay?.let { overlayGroup ->
                            for (i in 0 until overlayGroup.childCount) {
                                val child = overlayGroup.getChildAt(i)
                                if (child is android.widget.ImageButton) {
                                    child.visibility = android.view.View.GONE
                                }
                            }
                        }
                        
                        // Hide all ImageButtons/Buttons except play/pause and settings
                        fun hideUnwantedButtons(v: android.view.View) {
                            if (v is ImageButton || v is android.widget.Button) {
                                val idName = try {
                                    v.resources.getResourceEntryName(v.id).lowercase()
                                } catch (e: Exception) {
                                    ""
                                }
                                // Keep only: play_pause, settings, overflow_show
                                // Hide everything else (rew, ffwd, prev, next, etc.)
                                if (idName != "exo_play_pause" && 
                                    idName != "exo_settings" && idName != "exo_overflow_show") {
                                    v.visibility = android.view.View.GONE
                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "VideoPlayerDialog: Hid button in update (id=$idName)")
                                }
                            }
                            if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) {
                                    hideUnwantedButtons(v.getChildAt(i))
                                }
                            }
                        }
                        hideUnwantedButtons(view)
                        
                        // Also explicitly hide prev/next buttons
                        val prevButton = view.findViewById<ImageButton>(
                            androidx.media3.ui.R.id.exo_prev
                        )
                        prevButton?.visibility = android.view.View.GONE
                        
                        val nextButton = view.findViewById<ImageButton>(
                            androidx.media3.ui.R.id.exo_next
                        )
                        nextButton?.visibility = android.view.View.GONE
                        
                        val controllerView = view.findViewById<ViewGroup>(
                            androidx.media3.ui.R.id.exo_controller
                        )
                        controllerView?.let { controller ->
                            val playButton = controller.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_play_pause
                            )
                            playButton?.imageTintList = ColorStateList.valueOf(primaryColorInt)
                            
                            // Note: prev/next buttons are hidden, so no need to theme them
                            
                            // Hide rewind and fast forward explicitly
                            val rewindButton = controller.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_rew
                            )
                            rewindButton?.visibility = android.view.View.GONE
                            
                            val fastForwardButton = controller.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_ffwd
                            )
                            fastForwardButton?.visibility = android.view.View.GONE
                            
                            // Customize settings/overflow menu button (bottom right)
                            val settingsButton = controller.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_settings
                            ) ?: controller.findViewById<ImageButton>(
                                androidx.media3.ui.R.id.exo_overflow_show
                            )
                            settingsButton?.let {
                                it.imageTintList = ColorStateList.valueOf(primaryColorInt)
                            }
                            
                            val currentTime = controller.findViewById<TextView>(
                                androidx.media3.ui.R.id.exo_position
                            )
                            currentTime?.setTextColor(primaryColorInt)
                            
                            val totalTime = controller.findViewById<TextView>(
                                androidx.media3.ui.R.id.exo_duration
                            )
                            totalTime?.setTextColor(primaryColorInt)
                        }
                    }
                }
            )
            
            // Wavy progress bar overlay at bottom
            val progressState = remember {
                mutableStateOf(0f)
            }
            
            // Update progress state
            LaunchedEffect(currentPosition, duration) {
                progressState.value = if (duration > 0) {
                    (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
            
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 8.dp)
                    .pointerInput(Unit) {
                        // Combined tap and drag gestures for seeking
                        detectTapGestures(
                            onTap = { offset ->
                                // Tap to seek
                                if (duration > 0) {
                                    val progress = (offset.x / size.width).coerceIn(0f, 1f)
                                    val newPosition = (progress * duration).toLong()
                                    actualPlayer?.seekTo(newPosition)
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        // Track drag for seeking (tap and hold, then drag)
                        detectDragGestures(
                            onDragStart = { },
                            onDrag = { change, _ ->
                                if (duration > 0) {
                                    val progress = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val newPosition = (progress * duration).toLong()
                                    actualPlayer?.seekTo(newPosition)
                                }
                            },
                            onDragEnd = { },
                            onDragCancel = { }
                        )
                    }
            ) {
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                LinearWavyProgressIndicator(
                    progress = { progressState.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp) // Slightly taller for better visibility
                        .padding(horizontal = 16.dp),
                    color = primaryColor,
                    trackColor = primaryColor.copy(alpha = 0.3f),
                    // Flatten wave when paused or ended, animate when playing
                    amplitude = { 
                        if (isPlaying && playbackState == androidx.media3.common.Player.STATE_READY) {
                            1.0f // Maximum waviness when playing
                        } else {
                            0.0f // Flat when paused or ended
                        }
                    },
                    wavelength = WavyProgressIndicatorDefaults.LinearIndeterminateWavelength
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
                // Save button
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            saveVideoToGallery(
                                context, 
                                videoHttpUrl, 
                                mediaMessage.filename, 
                                mediaMessage.info.mimeType ?: "video/mp4",
                                authToken
                            )
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
                    onClick = {
                        // Stop the actual playing player before dismissing
                        actualPlayer?.let { player ->
                            player.stop()
                            player.release()
                        }
                        onDismiss()
                    },
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
