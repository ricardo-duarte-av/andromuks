package net.vrkknn.andromuks.utils

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest

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
    onSend: (caption: String) -> Unit
) {
    var caption by remember { mutableStateOf("") }
    val context = LocalContext.current
    
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
            usePlatformDefaultWidth = false // Allow full-width dialog
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
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
                    onClick = { onSend(caption) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send")
                    }
                }
            }
        }
    }
}

/**
 * Video player preview component with play/pause controls, progress bar, and duration
 */
@Composable
fun VideoPlayerPreview(uri: Uri) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var currentPosition by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var isUserSeeking by remember { mutableStateOf(false) }
    
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
                    
                    // Get duration when prepared
                    setOnPreparedListener { mp ->
                        duration = mp.duration
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
        
        // Play/Pause overlay button (centered)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .size(64.dp)
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
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f)
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
        
        // Progress bar and duration at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Progress slider
            Slider(
                value = currentPosition.toFloat(),
                onValueChange = { newValue ->
                    isUserSeeking = true
                    currentPosition = newValue.toInt()
                },
                onValueChangeFinished = {
                    videoView?.seekTo(currentPosition)
                    isUserSeeking = false
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
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
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isVideo) "Uploading video..." else "Uploading image...",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

