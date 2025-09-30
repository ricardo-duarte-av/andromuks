package net.vrkknn.andromuks.utils

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import net.vrkknn.andromuks.MediaMessage
import net.vrkknn.andromuks.utils.BlurHashUtils
import net.vrkknn.andromuks.utils.MediaUtils

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
    modifier: Modifier = Modifier
) {
    // Check if there's a caption to determine layout strategy
    // This determines whether to use separate image frame + caption or single bubble
    val hasCaption = !mediaMessage.caption.isNullOrBlank()
    
    if (hasCaption) {
        // With caption: Image gets its own frame with square corners, caption below
        Column(
            modifier = modifier.fillMaxWidth(0.8f)
        ) {
            // Image frame with square corners (8dp radius for modern look)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp), // Square corners for image frame
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                MediaContent(
                    mediaMessage = mediaMessage,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    isEncrypted = isEncrypted
                )
            }
            
            // Caption below the image frame with pointed corners (original message bubble style)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(
                    topStart = if (isMine) 12.dp else 4.dp,
                    topEnd = if (isMine) 4.dp else 12.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Text(
                    text = mediaMessage.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    } else {
        // Without caption: Image directly in message bubble with pointed corners
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
            tonalElevation = 1.dp
        ) {
            MediaContent(
                mediaMessage = mediaMessage,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = isEncrypted
            )
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
    isEncrypted: Boolean
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
                val imageUrl = remember(mediaMessage.url, isEncrypted) {
                    val httpUrl = MediaUtils.mxcToHttpUrl(mediaMessage.url, homeserverUrl)
                    // For encrypted media, add ?encrypted=true parameter
                    if (isEncrypted) {
                        val encryptedUrl = "$httpUrl?encrypted=true"
                        Log.d("Andromuks", "MediaMessage: Added encrypted=true to URL: $encryptedUrl")
                        encryptedUrl
                    } else {
                        httpUrl
                    }
                }
        
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
                            .addHeader("Cookie", "gomuks_auth=$authToken")
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = mediaMessage.filename,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = blurHashPainter,
                        error = blurHashPainter, // Use BlurHash as error fallback too
                        onSuccess = { 
                            Log.d("Andromuks", "✅ Image loaded successfully: $imageUrl")
                        },
                        onError = { state ->
                            Log.e("Andromuks", "❌ Image load failed: $imageUrl")
                            Log.e("Andromuks", "Error state: $state")
                            if (state is coil.request.ErrorResult) {
                                Log.e("Andromuks", "Error result: ${state.throwable}")
                                Log.e("Andromuks", "Error message: ${state.throwable.message}")
                            }
                        },
                        onLoading = { state ->
                            Log.d("Andromuks", "⏳ Image loading: $imageUrl, state: $state")
                        }
                    )
                } else {
                    // Video placeholder
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
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
                            if (mediaMessage.info.width > 0 && mediaMessage.info.height > 0) {
                                Text(
                                    text = "${mediaMessage.info.width}×${mediaMessage.info.height}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
