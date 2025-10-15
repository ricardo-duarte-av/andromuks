package net.vrkknn.andromuks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.Build
import android.util.Log
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.MediaCache
import net.vrkknn.andromuks.utils.ImageLoaderSingleton


@Composable
fun AvatarImage(
    mxcUrl: String?,
    homeserverUrl: String,
    authToken: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    userId: String? = null,
    displayName: String? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Use shared ImageLoader singleton with optimized memory cache
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    
    // Get avatar URL without preemptive loading - just check cache and convert MXC
    val avatarUrl = remember(mxcUrl) {
        AvatarUtils.getAvatarUrl(context, mxcUrl, homeserverUrl)
    }
    
    // Track if the image failed to load
    var imageLoadFailed by remember(avatarUrl) { mutableStateOf(false) }
    
    // Compute fallback info
    val fallbackLetter = remember(displayName, userId, fallbackText) {
        if (userId != null) {
            AvatarUtils.getFallbackCharacter(displayName, userId)
        } else {
            fallbackText.take(1).uppercase()
        }
    }
    
    val backgroundColor = remember(userId) {
        if (userId != null) {
            val colorHex = AvatarUtils.getUserColor(userId)
            Color(android.graphics.Color.parseColor("#$colorHex"))
        } else {
            null // Will use MaterialTheme color
        }
    }
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor ?: MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        // Show text fallback if no URL or if image failed to load
        if (avatarUrl == null || imageLoadFailed) {
            Text(
                text = fallbackLetter,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        } else {
            // We have an avatar URL and haven't failed yet - try to load it
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .apply {
                        // Only add auth header for HTTP URLs, not for file:// URIs
                        if (avatarUrl.startsWith("http")) {
                            addHeader("Cookie", "gomuks_auth=$authToken")
                        }
                    }
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onSuccess = {
                    imageLoadFailed = false
                    // Success - cache in background if not already cached and it's an HTTP URL
                    if (mxcUrl != null && avatarUrl.startsWith("http")) {
                        coroutineScope.launch {
                            MediaCache.downloadAndCache(context, mxcUrl, avatarUrl, authToken)
                            MediaCache.cleanupCache(context)
                        }
                    }
                },
                onError = { state ->
                    // Mark as failed so fallback shows
                    imageLoadFailed = true
                    
                    if (state is coil.request.ErrorResult) {
                        // Handle cache invalidation for permanent errors
                        net.vrkknn.andromuks.utils.CacheUtils.handleImageLoadError(
                            imageUrl = avatarUrl,
                            throwable = state.throwable,
                            imageLoader = imageLoader,
                            context = "Avatar"
                        )
                    }
                }
            )
        }
    }
}

