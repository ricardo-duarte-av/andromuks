package net.vrkknn.andromuks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import android.os.Build
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.BlurHashUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.CircleAvatarCache
import androidx.compose.foundation.Image
import kotlin.math.max
import kotlin.math.roundToInt


@Composable
fun AvatarImage(
    mxcUrl: String?,
    homeserverUrl: String,
    authToken: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    userId: String? = null,
    displayName: String? = null,
    blurHash: String? = null, // AVATAR LOADING OPTIMIZATION: BlurHash for placeholder
    isVisible: Boolean = true, // AVATAR LOADING OPTIMIZATION: Lazy loading control
    useCircleCache: Boolean = false, // CIRCLE AVATAR CACHE: Use CircleAvatarCache for RoomListScreen
    isScrollingFast: Boolean = false // PERFORMANCE: Suspend avatar loading during fast scrolling
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    
    // Use shared ImageLoader singleton with optimized memory cache
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    
    // Get avatar URL - use CircleAvatarCache if useCircleCache is true (for RoomListScreen)
    // PERFORMANCE FIX: Use getAvatarUrlForRoomList which checks CircleAvatarCache first, avoiding double checks
    var avatarUrl by remember(mxcUrl, useCircleCache) {
        if (useCircleCache && mxcUrl != null) {
            // For RoomListScreen: Check CircleAvatarCache -> MediaCache -> Coil -> Network
            // Start with null, will be set async by LaunchedEffect
            mutableStateOf<String?>(null)
        } else {
            // Normal flow: Check MediaCache -> Coil -> Network
            mutableStateOf(AvatarUtils.getAvatarUrl(context, mxcUrl, homeserverUrl))
        }
    }
    
    // For RoomListScreen, check CircleAvatarCache first, then fall back to MediaCache
    LaunchedEffect(mxcUrl, useCircleCache) {
        if (useCircleCache && mxcUrl != null) {
            // Use getAvatarUrlForRoomList which checks in correct order: CircleAvatarCache -> MediaCache -> Coil -> Network
            avatarUrl = AvatarUtils.getAvatarUrlForRoomList(context, mxcUrl, homeserverUrl)
        }
    }
    
    // CRITICAL FIX: Use a stable key (userId or fallbackText) instead of avatarUrl for state persistence
    // This prevents state reset when avatarUrl is recalculated (even if it's the same value)
    // The stable key ensures state persists across room list updates (sync_complete recompositions)
    val stableKey = userId ?: fallbackText
    
    // Track if the image failed to load - key by stableKey to persist across recompositions
    var imageLoadFailed by remember(stableKey) { mutableStateOf(false) }
    
    // PERFORMANCE: Track if image has successfully loaded - once loaded, show it even during fast scrolling
    var imageHasLoaded by remember(stableKey) { mutableStateOf(false) }
    
    // CRITICAL FIX: Initialize shouldLoadImage based on avatarUrl, but persist state per stableKey
    // This prevents fallback flash: if avatarUrl exists, start with shouldLoadImage=true
    // State persists across recompositions based on stableKey (room/user ID), not avatarUrl
    var shouldLoadImage by remember(stableKey) { 
        // Initialize to true if avatarUrl exists - AsyncImage handles cached images efficiently
        mutableStateOf(avatarUrl != null && isVisible)
    }
    
    // AVATAR LOADING OPTIMIZATION: Update shouldLoadImage when visibility or avatarUrl changes
    // PERFORMANCE: Only prevent LOADING during fast scrolling, not DISPLAY of already-loaded images
    LaunchedEffect(isVisible, avatarUrl, isScrollingFast) {
        if (isVisible && avatarUrl != null) {
            // Only allow loading if not fast scrolling OR image has already loaded
            // Once loaded, image stays visible even during fast scrolling
            shouldLoadImage = !isScrollingFast || imageHasLoaded
        }
        // Don't reset shouldLoadImage when item becomes invisible - keep it true
        // This ensures cached images show instantly when scrolling back into view
    }
    
    
    // AVATAR LOADING OPTIMIZATION: Decode BlurHash asynchronously to prevent UI thread blocking
    var placeholderBitmap by remember(blurHash, size) {
        mutableStateOf<ImageBitmap?>(null)
    }
    
    LaunchedEffect(blurHash, size) {
        if (blurHash != null && blurHash.isNotBlank()) {
            withContext(Dispatchers.Default) {
                val pixelSize = size.value.toInt()
                val bitmap = BlurHashUtils.decodeBlurHash(blurHash, pixelSize, pixelSize)
                if (bitmap != null) {
                    placeholderBitmap = bitmap.asImageBitmap()
                }
            }
        } else {
            placeholderBitmap = null
        }
    }
    
    val targetPixelSize = remember(size, density, useCircleCache) {
        val rawPx = with(density) { size.toPx() }.roundToInt()
        // PERFORMANCE FIX: For RoomListScreen, request smaller images to reduce decoding overhead
        // We only need 48dp = ~144px, not 512px. This reduces memory and decoding time.
        if (useCircleCache) {
            // Request exactly what we need (48dp = ~144px on most devices)
            max(rawPx, 64).coerceAtMost(256) // Cap at 256px max for room list
        } else {
            max(rawPx, 64) // request at least 64px to keep clarity when density is low
        }
    }
    
    // Compute fallback info
    val fallbackLetter = remember(displayName, userId, fallbackText, size) {
        val letter = if (userId != null) {
            AvatarUtils.getFallbackCharacter(displayName, userId)
        } else {
            fallbackText.take(1).uppercase()
        }
        
        // For small avatars (read markers), use circled letter emoji for better visibility
        // Circled letters: ⓐ-ⓩ (U+24B6-U+24CF) for lowercase, Ⓐ-Ⓩ (U+24B6-U+24CF) for uppercase
        if (size.value <= 16) { // Small avatars (read markers are 14.dp)
            val char = letter.firstOrNull() ?: '?'
            if (char.isLetter()) {
                val base = if (char.isUpperCase()) 0x24B6 else 0x24D0 // Ⓐ or ⓐ
                val offset = char.code - (if (char.isUpperCase()) 'A'.code else 'a'.code)
                if (offset in 0..25) {
                    String(Character.toChars(base + offset))
                } else {
                    letter
                }
            } else {
                letter
            }
        } else {
            letter
        }
    }
    
    // Calculate proportional font size based on avatar size (approximately 55% of circle size)
    // This ensures the letter/emoji is properly sized and centered for all circle sizes
    val fallbackFontSize = remember(size, density) {
        // Convert size to pixels, calculate 55% of it, then convert back to sp
        val sizeInPx = with(density) { size.toPx() }
        val fontSizeInPx = sizeInPx * 0.55f
        with(density) { fontSizeInPx.toSp() }
    }
    
    val backgroundColor = remember(userId) {
        if (userId != null) {
            val colorInt = AvatarUtils.getUserColor(userId)
            Color(colorInt)
        } else {
            null // Will use MaterialTheme color
        }
    }
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                // Only add background when showing fallback (no URL or load failed)
                if (avatarUrl == null || imageLoadFailed) {
                    Modifier.background(backgroundColor ?: MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // AVATAR LOADING OPTIMIZATION: Show placeholder, fallback, or image based on state
        val placeholder = placeholderBitmap
        when {
            // Show placeholder image if available and we haven't loaded the real image yet
            placeholder != null && !shouldLoadImage -> {
                Image(
                    bitmap = placeholder,
                    contentDescription = "Avatar placeholder",
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            // Show text fallback if no URL, failed to load, or not visible yet
            avatarUrl == null || imageLoadFailed || !shouldLoadImage -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = fallbackLetter,
                        fontSize = fallbackFontSize,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeight = fallbackFontSize
                    )
                }
            }
            // Load the actual avatar image with lazy loading optimization
            else -> {
                // Store avatarUrl in local variable to avoid smart cast issues with delegated property
                val currentAvatarUrl = avatarUrl
                
                if (currentAvatarUrl != null && shouldLoadImage) {
                    // PERFORMANCE: Only render AsyncImage (which triggers loading) if shouldLoadImage is true
                    // shouldLoadImage is false during fast scrolling (unless image already loaded)
                    // Once image loads, it stays visible even during fast scrolling
                    val avatarUrlForRequest = currentAvatarUrl // Non-null assertion for smart cast
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(avatarUrlForRequest)
                            .apply {
                                size(targetPixelSize)
                                // Only add auth header for HTTP URLs, not for file:// URIs
                                if (avatarUrlForRequest.startsWith("http")) {
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
                            imageHasLoaded = true // Mark as loaded - will stay visible even during fast scrolling
                            
                            // CIRCLE AVATAR CACHE: Cache square thumbnail for RoomListScreen
                            if (useCircleCache && mxcUrl != null) {
                                coroutineScope.launch {
                                    // Only cache if not already in CircleAvatarCache
                                    val circleCached = CircleAvatarCache.getCachedFile(context, mxcUrl)
                                    if (circleCached == null) {
                                        // Only cache if we loaded from MediaCache or network (not from CircleAvatarCache)
                                        // If avatarUrlForRequest is a file path and not from CircleAvatarCache, it's from MediaCache
                                        val isFromCircleCache = avatarUrlForRequest.contains("circle_avatar_cache")
                                        if (!isFromCircleCache) {
                                            // Cache circular version - use the source URL that was actually loaded
                                            CircleAvatarCache.cacheCircularAvatar(
                                                context = context,
                                                mxcUrl = mxcUrl,
                                                sourceImageUrl = avatarUrlForRequest,
                                                imageLoader = imageLoader,
                                                authToken = authToken
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // AVATAR LOADING OPTIMIZATION: Background caching with cleanup
                            // PERFORMANCE FIX: Skip MediaCache download if we loaded from CircleAvatarCache
                            // This prevents the double cache check we saw in logs
                            if (mxcUrl != null && avatarUrlForRequest.startsWith("http") && !useCircleCache) {
                                // Only download to MediaCache if NOT using CircleAvatarCache
                                // (CircleAvatarCache items are already processed, no need for MediaCache)
                                coroutineScope.launch {
                                    // Check if already cached to avoid redundant downloads
                                    if (IntelligentMediaCache.getCachedFile(context, mxcUrl) == null) {
                                        IntelligentMediaCache.downloadAndCache(context, mxcUrl, avatarUrlForRequest, authToken)
                                        IntelligentMediaCache.cleanupCache(context)
                                    }
                                }
                            }
                        },
                        onError = {
                            // Mark as failed so fallback shows
                            imageLoadFailed = true
                        }
                    )
                } else if (currentAvatarUrl != null && !imageHasLoaded) {
                    // PERFORMANCE: During fast scrolling, show fallback if image hasn't loaded yet
                    // Once image loads (imageHasLoaded = true), it will be shown above via AsyncImage
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = fallbackLetter,
                            fontSize = fallbackFontSize,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            lineHeight = fallbackFontSize
                        )
                    }
                }
            }
        }
    }
}

