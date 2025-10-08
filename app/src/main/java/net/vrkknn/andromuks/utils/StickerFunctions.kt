package net.vrkknn.andromuks.utils

import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.TimelineEvent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Data class to hold sticker information extracted from a timeline event.
 * 
 * @param url The MXC URL of the sticker image
 * @param body Alternative text for the sticker
 * @param width Declared width of the sticker (from info.w)
 * @param height Declared height of the sticker (from info.h)
 */
data class StickerMessage(
    val url: String,
    val body: String,
    val width: Int,
    val height: Int
)

/**
 * Extracts sticker information from a timeline event.
 * Handles both unencrypted (m.sticker) and encrypted sticker events.
 * 
 * @param event The timeline event to parse
 * @return StickerMessage if the event contains valid sticker data, null otherwise
 */
fun extractStickerFromEvent(event: TimelineEvent): StickerMessage? {
    // For unencrypted stickers, use content
    // For encrypted stickers, use decrypted content
    val stickerContent = when {
        event.type == "m.sticker" -> event.content
        event.type == "m.room.encrypted" && event.decryptedType == "m.sticker" -> event.decrypted
        else -> null
    } ?: return null
    
    val url = stickerContent.optString("url", "")
    val body = stickerContent.optString("body", "")
    val info = stickerContent.optJSONObject("info")
    
    if (url.isBlank() || info == null) {
        Log.w("Andromuks", "StickerFunctions: Invalid sticker data - url='$url', info=${info != null}")
        return null
    }
    
    val width = info.optInt("w", 0)
    val height = info.optInt("h", 0)
    
    if (width <= 0 || height <= 0) {
        Log.w("Andromuks", "StickerFunctions: Invalid sticker dimensions - width=$width, height=$height")
        return null
    }
    
    Log.d("Andromuks", "StickerFunctions: Extracted sticker - url=$url, body=$body, dimensions=${width}x${height}")
    return StickerMessage(url, body, width, height)
}

/**
 * Format timestamp for sticker messages
 */
private fun formatStickerTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(date)
}

/**
 * Displays timestamp inside sticker bubble (for consecutive messages)
 */
@Composable
private fun StickerBubbleTimestamp(
    timestamp: Long,
    isMine: Boolean,
    isConsecutive: Boolean
) {
    if (isConsecutive) {
        Text(
            text = formatStickerTimestamp(timestamp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .wrapContentWidth(if (isMine) Alignment.End else Alignment.Start)
                .padding(top = 2.dp) // Minimal top padding, no horizontal padding (already in Column)
        )
    }
}

/**
 * Displays a sticker message in a Material3 bubble with proper aspect ratio and styling.
 * 
 * This function renders sticker content with proper aspect ratio constraints based on the
 * declared width and height from the event. It supports both encrypted and unencrypted stickers
 * with appropriate URL parameters and authentication headers. Stickers also use the MediaCache
 * for efficient loading and caching.
 * 
 * @param stickerMessage StickerMessage object containing URL, body (alt text), width, and height
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing media
 * @param isMine Whether this message was sent by the current user (affects bubble styling)
 * @param isEncrypted Whether this is an encrypted sticker (adds ?encrypted=true to URL)
 * @param event The timeline event (for MessageBubbleWithMenu integration)
 * @param timestamp Timestamp of the message
 * @param isConsecutive Whether this is a consecutive message from the same sender
 * @param onReply Callback when user wants to reply to this sticker
 * @param onReact Callback when user wants to react to this sticker
 * @param onEdit Callback when user wants to edit (not applicable to stickers, but kept for consistency)
 * @param onDelete Callback when user wants to delete this sticker
 * @param modifier Modifier to apply to the sticker message container
 */
@Composable
fun StickerMessage(
    stickerMessage: StickerMessage,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    isEncrypted: Boolean = false,
    event: TimelineEvent? = null,
    timestamp: Long? = null,
    isConsecutive: Boolean = false,
    onReply: () -> Unit = {},
    onReact: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showStickerViewer by remember { mutableStateOf(false) }
    
    // Show sticker viewer dialog when sticker is tapped
    if (showStickerViewer) {
        StickerViewerDialog(
            stickerMessage = stickerMessage,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            isEncrypted = isEncrypted,
            onDismiss = { showStickerViewer = false }
        )
    }
    
    // Stickers are displayed without a caption bubble, just the image
    if (event != null) {
        MessageBubbleWithMenu(
            event = event,
            bubbleColor = MaterialTheme.colorScheme.surfaceVariant,
            bubbleShape = RoundedCornerShape(
                topStart = if (isMine) 12.dp else 4.dp,
                topEnd = if (isMine) 4.dp else 12.dp,
                bottomStart = 12.dp,
                bottomEnd = 12.dp
            ),
            modifier = modifier
                .wrapContentWidth()
                .wrapContentHeight(),
            onReply = onReply,
            onReact = onReact,
            onEdit = onEdit,
            onDelete = onDelete
        ) {
            // Minimal padding for stickers - tight fit with just a small border
            Column(
                modifier = Modifier.padding(4.dp)
            ) {
                StickerContent(
                    stickerMessage = stickerMessage,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    isEncrypted = isEncrypted,
                    onStickerClick = { showStickerViewer = true }
                )
                
                // Timestamp (for consecutive messages)
                if (timestamp != null) {
                    StickerBubbleTimestamp(
                        timestamp = timestamp,
                        isMine = isMine,
                        isConsecutive = isConsecutive
                    )
                }
            }
        }
    }
}

/**
 * Renders the actual sticker content with proper dimensions and caching.
 * 
 * @param stickerMessage StickerMessage object containing URL, body (alt text), width, and height
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing media
 * @param isEncrypted Whether this is encrypted media (adds ?encrypted=true to URL)
 * @param onStickerClick Callback when the sticker is clicked
 */
@Composable
private fun StickerContent(
    stickerMessage: StickerMessage,
    homeserverUrl: String,
    authToken: String,
    isEncrypted: Boolean,
    onStickerClick: () -> Unit = {}
) {
    // Use actual pixel dimensions from the sticker metadata
    // Display stickers at 100% zoom (1 pixel = 1 dp) for crisp rendering
    // Only scale down if the sticker is too large (max 256dp to allow for reasonable sticker sizes)
    val maxStickerDimension = 256.dp
    
    val actualWidth = stickerMessage.width.dp
    val actualHeight = stickerMessage.height.dp
    
    BoxWithConstraints {
        // Calculate final dimensions - use actual size but cap at max dimension
        val stickerWidth: androidx.compose.ui.unit.Dp
        val stickerHeight: androidx.compose.ui.unit.Dp
        
        if (actualWidth > maxStickerDimension || actualHeight > maxStickerDimension) {
            // Sticker is too large, scale it down proportionally
            val aspectRatio = stickerMessage.width.toFloat() / stickerMessage.height.toFloat()
            if (aspectRatio >= 1f) {
                // Wider than tall - constrain width
                stickerWidth = maxStickerDimension
                stickerHeight = maxStickerDimension / aspectRatio
            } else {
                // Taller than wide - constrain height
                stickerHeight = maxStickerDimension
                stickerWidth = maxStickerDimension * aspectRatio
            }
        } else {
            // Sticker is reasonably sized, use actual dimensions (100% zoom)
            stickerWidth = actualWidth
            stickerHeight = actualHeight
        }
        
        Box(
            modifier = Modifier
                .width(stickerWidth)
                .height(stickerHeight)
        ) {
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            
            // Create ImageLoader with GIF support (stickers can be animated)
            val imageLoader = remember {
                ImageLoader.Builder(context)
                    .components {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            add(ImageDecoderDecoder.Factory())
                        } else {
                            add(GifDecoder.Factory())
                        }
                    }
                    .build()
            }
            
            // Check if we have a cached version first
            val cachedFile = remember(stickerMessage.url) {
                MediaCache.getCachedFile(context, stickerMessage.url)
            }
            
            val imageUrl = remember(stickerMessage.url, isEncrypted, cachedFile) {
                if (cachedFile != null) {
                    // Use cached file
                    Log.d("Andromuks", "StickerMessage: Using cached file: ${cachedFile.absolutePath}")
                    cachedFile.absolutePath
                } else {
                    // Use HTTP URL
                    val httpUrl = MediaUtils.mxcToHttpUrl(stickerMessage.url, homeserverUrl)
                    if (isEncrypted) {
                        val encryptedUrl = "$httpUrl?encrypted=true"
                        Log.d("Andromuks", "StickerMessage: Added encrypted=true to URL: $encryptedUrl")
                        encryptedUrl
                    } else {
                        httpUrl
                    }
                }
            }
            
            // Download and cache if not already cached
            LaunchedEffect(stickerMessage.url) {
                if (cachedFile == null) {
                    coroutineScope.launch {
                        val httpUrl = MediaUtils.mxcToHttpUrl(stickerMessage.url, homeserverUrl)
                        val finalUrl = if (isEncrypted) "$httpUrl?encrypted=true" else httpUrl ?: ""
                        MediaCache.downloadAndCache(context, stickerMessage.url, finalUrl, authToken)
                        // Clean up cache if needed
                        MediaCache.cleanupCache(context)
                    }
                }
            }
            
            // Simple gray placeholder for stickers (no BlurHash typically)
            val placeholderPainter = remember {
                BitmapPainter(
                    BlurHashUtils.createPlaceholderBitmap(
                        32, 32,
                        androidx.compose.ui.graphics.Color.Gray
                    )
                )
            }
            
            Log.d("Andromuks", "StickerMessage: Loading sticker from $imageUrl")
            
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
                contentDescription = stickerMessage.body,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onStickerClick() }
                            // Don't handle onLongPress - let it bubble up to MessageBubbleWithMenu
                        )
                    },
                placeholder = placeholderPainter,
                error = placeholderPainter,
                onSuccess = {
                    Log.d("Andromuks", "✅ Sticker loaded successfully: $imageUrl")
                },
                onError = { state ->
                    Log.e("Andromuks", "❌ Sticker load failed: $imageUrl")
                    Log.e("Andromuks", "Error state: $state")
                    if (state is coil.request.ErrorResult) {
                        Log.e("Andromuks", "Error result: ${state.throwable}")
                        Log.e("Andromuks", "Error message: ${state.throwable.message}")
                    }
                },
                onLoading = { state ->
                    Log.d("Andromuks", "⏳ Sticker loading: $imageUrl, state: $state")
                }
            )
        }
    }
}

/**
 * Fullscreen sticker viewer dialog with zoom and pan capabilities.
 * 
 * This dialog provides a fullscreen sticker viewing experience with:
 * - Pinch to zoom functionality
 * - Pan gestures when zoomed
 * - Close on tap/back gesture support
 * - Smooth animations and transitions
 * 
 * @param stickerMessage StickerMessage object containing the sticker to display
 * @param homeserverUrl Base URL of the Matrix homeserver for MXC URL conversion
 * @param authToken Authentication token for accessing media
 * @param isEncrypted Whether this is encrypted media (adds ?encrypted=true to URL)
 * @param onDismiss Callback when the dialog should be dismissed
 */
@Composable
private fun StickerViewerDialog(
    stickerMessage: StickerMessage,
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
            // Sticker with zoom and pan
            val context = LocalContext.current
            
            // Create ImageLoader with GIF support
            val imageLoader = remember {
                ImageLoader.Builder(context)
                    .components {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            add(ImageDecoderDecoder.Factory())
                        } else {
                            add(GifDecoder.Factory())
                        }
                    }
                    .build()
            }
            
            // Check if we have a cached version first
            val cachedFile = remember(stickerMessage.url) {
                MediaCache.getCachedFile(context, stickerMessage.url)
            }
            
            val imageUrl = remember(stickerMessage.url, isEncrypted, cachedFile) {
                if (cachedFile != null) {
                    // Use cached file
                    cachedFile.absolutePath
                } else {
                    // Use HTTP URL
                    val httpUrl = MediaUtils.mxcToHttpUrl(stickerMessage.url, homeserverUrl)
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
                contentDescription = stickerMessage.body,
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
                    Log.d("Andromuks", "✅ StickerViewer: Sticker loaded successfully: $imageUrl")
                },
                onError = { state ->
                    Log.e("Andromuks", "❌ StickerViewer: Sticker load failed: $imageUrl")
                    Log.e("Andromuks", "Error state: $state")
                }
            )
        }
    }
}

