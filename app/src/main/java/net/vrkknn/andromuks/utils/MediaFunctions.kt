package net.vrkknn.andromuks.utils

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import net.vrkknn.andromuks.TimelineEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onUserClick: (String) -> Unit = {}
) {
    // Check if the event supports HTML rendering (has sanitized_html or formatted_body)
    val supportsHtml = event != null && supportsHtmlRendering(event)
    
    if (supportsHtml && event != null) {
        // Use HTML rendering for caption
        HtmlMessageText(
            event = event,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            onMatrixUserClick = onUserClick
        )
    } else {
        // Fallback to plain text
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
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
    isConsecutive: Boolean
) {
    if (isConsecutive) {
        Text(
            text = if (editedBy != null) {
                "${formatMediaTimestamp(timestamp)} (edited)"
            } else {
                formatMediaTimestamp(timestamp)
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(if (isMine) Alignment.Start else Alignment.End)
                .padding(horizontal = 12.dp, vertical = 4.dp)
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
    onBubbleClick: (() -> Unit)? = null
) {
    var showImageViewer by remember { mutableStateOf(false) }
    var showVideoPlayer by remember { mutableStateOf(false) }
    
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
    
    // Check if this is a thread message to apply thread colors
    val isThreadMessage = event?.isThreadMessage() ?: false
    val mediaBubbleColor = if (isThreadMessage) {
        // Own thread messages: full opacity for emphasis
        // Others' thread messages: lighter for distinction
        if (isMine) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    
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
                modifier = modifier.fillMaxWidth(0.8f),
                isMine = isMine,
                myUserId = myUserId,
                powerLevels = powerLevels,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                appViewModel = appViewModel,
                onBubbleClick = onBubbleClick
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
                        }
                    )
                    
                    // Caption text below the image, inside the same bubble
                    MediaCaption(
                        caption = mediaMessage.caption,
                        event = event,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onUserClick = onUserClick
                    )
                    
                    // Timestamp (for consecutive messages)
                    if (timestamp != null) {
                        MediaBubbleTimestamp(
                            timestamp = timestamp,
                            editedBy = editedBy,
                            isMine = isMine,
                            isConsecutive = isConsecutive
                        )
                    }
                }
            }
        } else {
            Surface(
                modifier = modifier.fillMaxWidth(0.8f),
                shape = RoundedCornerShape(
                    topStart = if (isMine) 12.dp else 4.dp,
                    topEnd = if (isMine) 4.dp else 12.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 3.dp
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
                        }
                    )
                    
                    // Caption text below the image, inside the same bubble
                    MediaCaption(
                        caption = mediaMessage.caption,
                        event = event,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onUserClick = onUserClick
                    )
                    
                    // Timestamp (for consecutive messages)
                    if (timestamp != null) {
                        MediaBubbleTimestamp(
                            timestamp = timestamp,
                            editedBy = editedBy,
                            isMine = isMine,
                            isConsecutive = isConsecutive
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
                    .fillMaxWidth(0.8f)
                    .wrapContentHeight(),
                isMine = isMine,
                myUserId = myUserId,
                powerLevels = powerLevels,
                onReply = onReply,
                onReact = onReact,
                onEdit = onEdit,
                onDelete = onDelete,
                appViewModel = appViewModel,
                onBubbleClick = onBubbleClick
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
                        }
                    )
                    
                    // Timestamp (for consecutive messages)
                    if (timestamp != null) {
                        MediaBubbleTimestamp(
                            timestamp = timestamp,
                            editedBy = editedBy,
                            isMine = isMine,
                            isConsecutive = isConsecutive
                        )
                    }
                }
            }
        } else {
            Surface(
                modifier = modifier
                    .fillMaxWidth(0.8f) // Max 80% width
                    .wrapContentHeight(),
                shape = RoundedCornerShape(
                    topStart = if (isMine) 12.dp else 4.dp,
                    topEnd = if (isMine) 4.dp else 12.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 3.dp
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
                        }
                    )
                    
                    // Timestamp (for consecutive messages)
                    if (timestamp != null) {
                        MediaBubbleTimestamp(
                            timestamp = timestamp,
                            editedBy = editedBy,
                            isMine = isMine,
                            isConsecutive = isConsecutive
                        )
                    }
                }
            }
        }
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
    onImageClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Media container with aspect ratio
        val aspectRatio = if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
            mediaMessage.info.width.toFloat() / mediaMessage.info.height.toFloat()
        } else {
            16f / 9f // Default aspect ratio
        }
        
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val calculatedHeight = if (aspectRatio > 0) {
                (maxWidth / aspectRatio).coerceAtMost(300.dp) // Max height of 300dp
            } else {
                200.dp // Default height
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(calculatedHeight)
            ) {
                val context = LocalContext.current
                val coroutineScope = rememberCoroutineScope()
                
                // Use shared ImageLoader singleton with custom User-Agent
                val imageLoader = remember { ImageLoaderSingleton.get(context) }
                
                // Check if we have a cached version first
                val cachedFile = remember(mediaMessage.url) {
                    MediaCache.getCachedFile(context, mediaMessage.url)
                }
                
                val imageUrl = remember(mediaMessage.url, isEncrypted, cachedFile) {
                    if (cachedFile != null) {
                        // Use cached file
                        Log.d("Andromuks", "MediaMessage: Using cached file: ${cachedFile.absolutePath}")
                        cachedFile.absolutePath
                    } else {
                        // Use HTTP URL
                        val httpUrl = MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
                        if (isEncrypted) {
                            val encryptedUrl = "$httpUrl?encrypted=true"
                            Log.d("Andromuks", "MediaMessage: Added encrypted=true to URL: $encryptedUrl")
                            encryptedUrl
                        } else {
                            httpUrl
                        }
                    }
                }
                
                // NOTE: Coil handles caching automatically with memoryCachePolicy and diskCachePolicy
                // No need to manually download - would cause duplicate requests (Coil + okhttp)
        
                if (mediaMessage.msgType == "m.image") {
                    // Debug logging
                    Log.d("Andromuks", "MediaMessage: URL=$imageUrl, BlurHash=${mediaMessage.info.blurHash}, AuthToken=$authToken")
                    
                    val blurHashPainter = remember(mediaMessage.info.blurHash) {
                        mediaMessage.info.blurHash?.let { blurHash ->
                            Log.d("Andromuks", "Decoding BlurHash: $blurHash")
                            val bitmap = BlurHashUtils.decodeBlurHash(blurHash, 32, 32)
                            Log.d("Andromuks", "BlurHash decoded: ${bitmap != null}")
                            if (bitmap != null) {
                                val imageBitmap = bitmap.asImageBitmap()
                                Log.d("Andromuks", "BlurHash converted to ImageBitmap: ${imageBitmap.width}x${imageBitmap.height}")
                                Log.d("Andromuks", "BlurHash bitmap info: config=${bitmap.config}, hasAlpha=${bitmap.hasAlpha()}")
                                BitmapPainter(imageBitmap)
                            } else {
                                Log.w("Andromuks", "BlurHash decode failed, using fallback")
                                BitmapPainter(
                                    BlurHashUtils.createPlaceholderBitmap(
                                        32, 32, 
                                        androidx.compose.ui.graphics.Color.Gray
                                    )
                                )
                            }
                        } ?: run {
                            // Simple fallback placeholder without MaterialTheme
                            Log.d("Andromuks", "No BlurHash available, using simple fallback")
                            BitmapPainter(
                                BlurHashUtils.createPlaceholderBitmap(
                                    32, 32, 
                                    androidx.compose.ui.graphics.Color.Gray
                                )
                            )
                        }
                    }
                    
                    Log.d("Andromuks", "BlurHash painter created: ${blurHashPainter != null}")
                    
                    Log.d("Andromuks", "AsyncImage: Starting image load for $imageUrl")
                    
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
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onImageClick() }
                                    // Don't handle onLongPress - let it bubble up to MessageBubbleWithMenu
                                )
                            },
                        placeholder = blurHashPainter,
                        error = blurHashPainter, // Use BlurHash as error fallback too
                        onSuccess = { 
                            Log.d("Andromuks", "✅ Image loaded successfully: $imageUrl")
                        },
                        onError = { state ->
                            if (state is coil.request.ErrorResult) {
                                CacheUtils.handleImageLoadError(
                                    imageUrl = imageUrl,
                                    throwable = state.throwable,
                                    imageLoader = imageLoader,
                                    context = "Media"
                                )
                            }
                        },
                        onLoading = { state ->
                            Log.d("Andromuks", "⏳ Image loading: $imageUrl, state: $state")
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
                            val thumbnailHttpUrl = MediaUtils.mxcToHttpUrl(thumbnailUrl, homeserverUrl)
                            val thumbnailFinalUrl = if (mediaMessage.info.thumbnailIsEncrypted && thumbnailHttpUrl != null) {
                                "$thumbnailHttpUrl?encrypted=true"
                            } else {
                                thumbnailHttpUrl
                            }
                            
                            val thumbnailBlurHashPainter = remember(mediaMessage.info.thumbnailBlurHash) {
                                mediaMessage.info.thumbnailBlurHash?.let { blurHash ->
                                    val bitmap = BlurHashUtils.decodeBlurHash(blurHash, 32, 32)
                                    if (bitmap != null) {
                                        BitmapPainter(bitmap.asImageBitmap())
                                    } else {
                                        BitmapPainter(
                                            BlurHashUtils.createPlaceholderBitmap(
                                                32, 32,
                                                androidx.compose.ui.graphics.Color.Gray
                                            )
                                        )
                                    }
                                } ?: BitmapPainter(
                                    BlurHashUtils.createPlaceholderBitmap(
                                        32, 32,
                                        androidx.compose.ui.graphics.Color.Gray
                                    )
                                )
                            }
                            
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(thumbnailFinalUrl)
                                    .addHeader("Cookie", "gomuks_auth=$authToken")
                                    .memoryCachePolicy(CachePolicy.ENABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .build(),
                                imageLoader = imageLoader,
                                contentDescription = "Video thumbnail: ${mediaMessage.filename}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { onImageClick() }
                                        )
                                    },
                                placeholder = thumbnailBlurHashPainter,
                                error = thumbnailBlurHashPainter,
                                onSuccess = { 
                                    Log.d("Andromuks", "✅ Video thumbnail loaded: $thumbnailFinalUrl")
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
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
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
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Fallback: No thumbnail available, show placeholder
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
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
                    Log.d("Andromuks", "✅ ImageViewer: Image loaded successfully: $imageUrl")
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
            
            // Dispose player when dialog is dismissed
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
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
                onClick = onDismiss,
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
