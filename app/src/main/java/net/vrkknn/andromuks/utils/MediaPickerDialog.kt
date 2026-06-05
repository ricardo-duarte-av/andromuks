package net.vrkknn.andromuks.utils

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.SharedMediaItem
import net.vrkknn.andromuks.ui.components.ContainedExpressiveLoadingIndicator

/**
 * Per-item state when sending multiple media (caption and compress option for images).
 */
data class MediaPreviewItemSendState(
    val item: SharedMediaItem,
    val caption: String,
    val compressOriginal: Boolean
)

/**
 * Dialog to preview multiple selected media: swipe left/right, caption per item, then send all in queue.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaPreviewDialogMultiple(
    items: List<SharedMediaItem>,
    onDismiss: () -> Unit,
    onSendAll: (List<MediaPreviewItemSendState>) -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { items.size })
    var captionsByIndex by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var compressByIndex by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    val currentPage = pagerState.currentPage

    fun classifyMime(mime: String?): String {
        val m = mime?.lowercase() ?: return "file"
        return when {
            m.startsWith("image/") -> "image"
            m.startsWith("video/") -> "video"
            m.startsWith("audio/") -> "audio"
            else -> "file"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        LaunchedEffect(Unit) {
            dialogWindowProvider?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Send ${items.size} items",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                Text(
                    text = "${currentPage + 1} of ${items.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    val item = items[page]
                    val mime = item.mimeType ?: context.contentResolver.getType(item.uri)
                    val kind = classifyMime(mime)
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            when (kind) {
                                "video" -> VideoPlayerPreview(uri = item.uri)
                                "image" -> AsyncImage(
                                    model = ImageRequest.Builder(context).data(item.uri).crossfade(true).build(),
                                    contentDescription = "Image ${page + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                else -> FilePreview(uri = item.uri, mimeType = mime)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (kind == "image") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = compressByIndex[page] ?: false,
                                    onCheckedChange = { compressByIndex = compressByIndex + (page to it) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Compress image", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        OutlinedTextField(
                            value = captionsByIndex[page] ?: "",
                            onValueChange = { captionsByIndex = captionsByIndex + (page to it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Caption (optional)") },
                            placeholder = { Text("Write a caption...") },
                            maxLines = 2
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val list = items.mapIndexed { index, item ->
                            val kind = classifyMime(item.mimeType ?: context.contentResolver.getType(item.uri))
                            MediaPreviewItemSendState(
                                item = item,
                                caption = captionsByIndex[index] ?: "",
                                compressOriginal = kind == "image" && (compressByIndex[index] ?: false)
                            )
                        }
                        onSendAll(list)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send all (${items.size})")
                    }
                }
            }
        }
        } // Box
    }
}

/**
 * Dialog to preview selected media and add an optional caption before sending
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPreviewDialog(
    uri: Uri,
    isVideo: Boolean = false,
    isAudio: Boolean = false,
    isFile: Boolean = false,
    onDismiss: () -> Unit,
    onSend: (caption: String, compressOriginal: Boolean) -> Unit
) {
    var caption by remember { mutableStateOf("") }
    var compressOriginal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isImage = !isVideo && !isAudio && !isFile
    
    // Determine the appropriate title based on media type
    val title = when {
        isAudio -> "Send Audio"
        isFile -> "Send File"
        isVideo -> "Send Video"
        else -> "Send Image"
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        LaunchedEffect(Unit) {
            dialogWindowProvider?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top bar with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close"
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Media preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (isVideo) {
                        // Video player preview
                        VideoPlayerPreview(uri = uri)
                    } else if (isFile) {
                        // File preview (pdf thumbnail / text excerpt / chip fallback)
                        FilePreview(
                            uri = uri,
                            mimeType = context.contentResolver.getType(uri)
                        )
                    } else {
                        // Image preview
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(uri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Selected image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Compression checkbox (only for images)
                if (isImage) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = compressOriginal,
                            onCheckedChange = { compressOriginal = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Compress image",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Caption input
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Add a caption (optional)") },
                    placeholder = { Text("Write a caption...") },
                    maxLines = 3
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Send button
                Button(
                    onClick = { onSend(caption, compressOriginal) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        @Suppress("DEPRECATION")
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send")
                    }
                }
            }
        }
        } // Box
    }
}

/** Metadata resolved from a content URI for the file-preview header / chip. */
private data class FileMeta(val name: String?, val size: Long?)

private fun resolveFileMeta(context: android.content.Context, uri: Uri): FileMeta {
    var name: String? = null
    var size: Long? = null
    try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
    } catch (_: Exception) {
        // Fall back to nulls; the chip still renders with a generic label.
    }
    return FileMeta(name, size)
}

/** Pick a file icon based on the MIME type. */
private fun fileIconFor(mimeType: String?): ImageVector {
    val m = mimeType?.lowercase() ?: return Icons.Filled.InsertDriveFile
    return when {
        m == "application/pdf" -> Icons.Filled.PictureAsPdf
        m.startsWith("text/") || m == "application/json" || m == "application/xml" ->
            Icons.Filled.Description
        else -> Icons.Filled.InsertDriveFile
    }
}

private fun isTextMime(mimeType: String?): Boolean {
    val m = mimeType?.lowercase() ?: return false
    return m.startsWith("text/") ||
        m == "application/json" ||
        m == "application/xml" ||
        m == "application/javascript" ||
        m == "application/x-yaml"
}

/** Max bytes read for an inline text preview, to keep a huge log from OOMing the dialog. */
private const val TEXT_PREVIEW_LIMIT = 50 * 1024

/**
 * Preview for an attached file before sending. Renders, by MIME type:
 *  - application/pdf  → first-page thumbnail via the platform PdfRenderer
 *  - text (+ json/xml/js/yaml) → first ~50 KB in a scrollable monospace box
 *  - anything else    → a file chip (icon + name + size)
 *
 * Metadata (name/size) is resolved on the fly from the content URI, so the data
 * model stays untouched.
 */
@Composable
fun FilePreview(uri: Uri, mimeType: String?) {
    val context = LocalContext.current
    val meta by produceState(initialValue = FileMeta(null, null), uri) {
        value = withContext(Dispatchers.IO) { resolveFileMeta(context, uri) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header chip: icon + name + size — shown for every file kind.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = fileIconFor(mimeType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meta.name ?: "Unknown file",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = buildString {
                    meta.size?.let { append(Formatter.formatShortFileSize(context, it)) }
                    if (mimeType != null) {
                        if (isNotEmpty()) append(" • ")
                        append(mimeType)
                    }
                }
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Body: rich preview where we can render it, otherwise nothing extra.
        when {
            mimeType?.lowercase() == "application/pdf" ->
                PdfThumbnailPreview(uri = uri, modifier = Modifier.fillMaxSize())
            isTextMime(mimeType) ->
                TextFilePreview(uri = uri, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Renders the first page of a PDF as a bitmap thumbnail. */
@Composable
private fun PdfThumbnailPreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
                renderer = PdfRenderer(pfd)
                if (renderer.pageCount <= 0) return@withContext null
                renderer.openPage(0).use { page ->
                    // Scale page-0 to ~1080px wide for a crisp-but-bounded thumbnail.
                    val targetWidth = 1080
                    val scale = targetWidth.toFloat() / page.width.toFloat()
                    val width = targetWidth
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            } catch (_: Exception) {
                null
            } finally {
                try { renderer?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }
        }
        if (bitmap == null) failed = true
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "PDF preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            failed -> Text(
                text = "Preview unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> CircularProgressIndicator()
        }
    }
}

/** Renders the first ~50 KB of a text file in a scrollable monospace box. */
@Composable
private fun TextFilePreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by remember(uri) { mutableStateOf<String?>(null) }
    var truncated by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val result = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(TEXT_PREVIEW_LIMIT)
                    var read = 0
                    while (read < TEXT_PREVIEW_LIMIT) {
                        val n = stream.read(buffer, read, TEXT_PREVIEW_LIMIT - read)
                        if (n < 0) break
                        read += n
                    }
                    val more = stream.read() >= 0
                    String(buffer, 0, read, Charsets.UTF_8) to more
                }
            } catch (_: Exception) {
                null
            }
        }
        if (result == null) {
            failed = true
        } else {
            text = result.first
            truncated = result.second
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        val content = text
        when {
            content != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
                if (truncated) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "… preview truncated",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            failed -> Text(
                text = "Preview unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Video player preview component with play/pause controls, wavy progress bar, and duration
 */
@Composable
fun VideoPlayerPreview(uri: Uri) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val primaryColor = colorScheme.primary
    
    var isPlaying by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var currentPosition by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    
    // Progress state for wavy indicator
    val progressState = remember { mutableStateOf(0f) }
    
    // Update current position while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            videoView?.let { vv ->
                if (!isUserSeeking) {
                    currentPosition = vv.currentPosition
                    if (duration == 0) {
                        duration = vv.duration
                    }
                }
            }
            kotlinx.coroutines.delay(100) // Update every 100ms
        }
    }
    
    // Update progress state
    LaunchedEffect(currentPosition, duration) {
        progressState.value = if (duration > 0) {
            (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }
    
    // Clean up when composable is disposed
    DisposableEffect(uri) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }
    
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Video player
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    videoView = this
                    setVideoURI(uri)
                    
                    // Get duration when prepared and auto-start
                    setOnPreparedListener { mp ->
                        duration = mp.duration
                        isPrepared = true
                        // Auto-start the video
                        start()
                        isPlaying = true
                    }
                    
                    // Set up completion listener to reset play state
                    setOnCompletionListener {
                        isPlaying = false
                        currentPosition = 0
                        seekTo(0) // Reset to start
                    }
                    
                    // Set up error listener
                    setOnErrorListener { _, _, _ ->
                        isPlaying = false
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Play/Pause overlay button (centered, always visible but more transparent when playing)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    videoView?.let { vv ->
                        if (isPlaying) {
                            vv.pause()
                            isPlaying = false
                        } else {
                            vv.start()
                            isPlaying = true
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = if (isPlaying) 0.3f else 0.5f)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            }
        }
        
        // Wavy progress bar and duration at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Wavy progress indicator
            @OptIn(ExperimentalMaterial3ExpressiveApi::class)
            LinearWavyProgressIndicator(
                progress = { progressState.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = primaryColor,
                trackColor = primaryColor.copy(alpha = 0.3f),
                // Flatten wave when paused or ended, animate when playing
                amplitude = { 
                    if (isPlaying && isPrepared) {
                        1.0f // Maximum waviness when playing
                    } else {
                        0.0f // Flat when paused or ended
                    }
                },
                wavelength = WavyProgressIndicatorDefaults.LinearIndeterminateWavelength
            )
            
            // Time display (current / total)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMillis(currentPosition),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Text(
                    text = formatMillis(duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Format milliseconds to MM:SS format
 */
private fun formatMillis(millis: Int): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

/**
 * Loading dialog shown during upload
 */
@Composable
fun UploadingDialog(
    isVideo: Boolean = false,
    onDismiss: () -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ContainedExpressiveLoadingIndicator(
                    modifier = Modifier
                        .size(96.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentPadding = 12.dp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isVideo) "Uploading video..." else "Uploading image...",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

