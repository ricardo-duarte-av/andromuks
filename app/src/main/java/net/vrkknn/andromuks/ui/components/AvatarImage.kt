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
    
    // State to hold the final avatar URL (http://, file://, or data: URI)
    // Use stable key to prevent unnecessary state resets
    var avatarUrl by remember(mxcUrl, userId) { mutableStateOf<String?>(null) }
    
    // Load avatar with fallback support
    // Only re-execute when mxcUrl or userId changes (not displayName)
    LaunchedEffect(mxcUrl, userId) {
        if (userId != null) {
            // Use new fallback avatar system
            val url = AvatarUtils.getAvatarUrlWithFallback(
                context = context,
                mxcUrl = mxcUrl,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                displayName = displayName,
                userId = userId
            )
            avatarUrl = url
            Log.d("Andromuks", "AvatarImage: Got avatar URL with fallback: $url")
        } else {
            // Fallback to old behavior if no userId provided
            val cachedFile = if (mxcUrl != null) MediaCache.getCachedFile(context, mxcUrl) else null
            
            if (cachedFile != null) {
                avatarUrl = cachedFile.absolutePath
                Log.d("Andromuks", "AvatarImage: Using cached file: ${cachedFile.absolutePath}")
            } else if (mxcUrl != null) {
                val httpUrl = AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
                avatarUrl = httpUrl
                Log.d("Andromuks", "AvatarImage: Using HTTP URL: $httpUrl")
                
                // Download and cache in background
                if (httpUrl != null) {
                    coroutineScope.launch {
                        MediaCache.downloadAndCache(context, mxcUrl, httpUrl, authToken)
                        MediaCache.cleanupCache(context)
                    }
                }
            } else {
                avatarUrl = null
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
        // Check if this is a data URI SVG fallback - render it directly instead of using AsyncImage
        if (avatarUrl != null && avatarUrl!!.startsWith("data:image/svg+xml,")) {
            // This is our generated SVG fallback - render it as colored circle with text
            val fallbackLetter = if (userId != null) {
                AvatarUtils.getFallbackCharacter(displayName, userId)
            } else {
                fallbackText.take(1).uppercase()
            }
            
            val backgroundColor = if (userId != null) {
                // Parse color from the data URI or get from userId
                val colorHex = AvatarUtils.getUserColor(userId)
                Color(android.graphics.Color.parseColor("#$colorHex"))
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
            
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fallbackLetter,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        } else if (avatarUrl != null) {
            // Regular image URL (http://, file://, etc.) - use AsyncImage
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .apply {
                        // Only add auth header for HTTP URLs, not for file:// URIs
                        if (avatarUrl?.startsWith("http") == true) {
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
                    Log.d("Andromuks", "✅ AvatarImage: Avatar loaded successfully: $avatarUrl")
                },
                onError = { state ->
                    if (state is coil.request.ErrorResult) {
                        // Handle cache invalidation for permanent errors
                        net.vrkknn.andromuks.utils.CacheUtils.handleImageLoadError(
                            imageUrl = avatarUrl ?: "",
                            throwable = state.throwable,
                            imageLoader = imageLoader,
                            context = "Avatar"
                        )
                        
                        // If we have userId, generate fallback (will be rendered as colored circle)
                        if (userId != null) {
                            coroutineScope.launch {
                                val svgFallback = AvatarUtils.generateLocalFallbackAvatar(displayName, userId)
                                avatarUrl = svgFallback
                                Log.d("Andromuks", "AvatarImage: Generated fallback after error")
                            }
                        }
                    }
                }
            )
        } else {
            // Text fallback when no URL is available
            val fallbackLetter = if (userId != null) {
                AvatarUtils.getFallbackCharacter(displayName, userId)
            } else {
                fallbackText.take(1).uppercase()
            }
            
            val backgroundColor = if (userId != null) {
                val colorHex = AvatarUtils.getUserColor(userId)
                Color(android.graphics.Color.parseColor("#$colorHex"))
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
            
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fallbackLetter,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }
    }
}

