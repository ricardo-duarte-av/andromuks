package net.vrkknn.andromuks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.Build
import android.util.Log
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.MediaCache


@Composable
fun AvatarImage(
    mxcUrl: String?,
    homeserverUrl: String,
    authToken: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
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
    val cachedFile = remember(mxcUrl) {
        if (mxcUrl != null) MediaCache.getCachedFile(context, mxcUrl) else null
    }
    
    val imageUrl = remember(mxcUrl, cachedFile) {
        if (mxcUrl.isNullOrBlank()) {
            null
        } else if (cachedFile != null) {
            // Use cached file
            Log.d("Andromuks", "AvatarImage: Using cached file: ${cachedFile.absolutePath}")
            cachedFile.absolutePath
        } else {
            // Use HTTP URL
            val httpUrl = AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
            Log.d("Andromuks", "AvatarImage: Using HTTP URL: $httpUrl")
            httpUrl
        }
    }
    
    // Download and cache if not already cached
    LaunchedEffect(mxcUrl) {
        if (mxcUrl != null && cachedFile == null) {
            coroutineScope.launch {
                val httpUrl = AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
                if (httpUrl != null) {
                    MediaCache.downloadAndCache(context, mxcUrl, httpUrl, authToken)
                    // Clean up cache if needed
                    MediaCache.cleanupCache(context)
                }
            }
        }
    }
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
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
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onSuccess = {
                    Log.d("Andromuks", "✅ AvatarImage: Avatar loaded successfully: $imageUrl")
                },
                onError = { state ->
                    Log.e("Andromuks", "❌ AvatarImage: Avatar load failed: $imageUrl")
                    Log.e("Andromuks", "Error state: $state")
                }
            )
        } else {
            Text(
                text = fallbackText.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

