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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import android.os.Build
import android.util.Log
import coil.compose.AsyncImage
import net.vrkknn.andromuks.BuildConfig
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.CircleAvatarCache
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
    isVisible: Boolean = true, // AVATAR LOADING OPTIMIZATION: Lazy loading control
    useCircleCache: Boolean = false, // CIRCLE AVATAR CACHE: Use CircleAvatarCache for RoomListScreen
    isScrollingFast: Boolean = false // PERFORMANCE: Suspend avatar loading during fast scrolling
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Use shared ImageLoader singleton with optimized memory cache
    val imageLoader = remember { ImageLoaderSingleton.get(context) }

    // Resolve effective identity for colour/letter generation
    val effectiveUserId = userId ?: fallbackText
    val effectiveDisplayName = displayName ?: fallbackText

    // SVG data URI used when mxcUrl is null or media fails to load.
    // Scales perfectly at any dp size; consistent with the web frontend.
    val svgFallbackUri = remember(effectiveUserId, effectiveDisplayName) {
        AvatarUtils.generateLocalFallbackAvatar(effectiveDisplayName, effectiveUserId)
    }

    // Get avatar URL - use CircleAvatarCache if useCircleCache is true (for RoomListScreen)
    // PERFORMANCE: For room list, try the synchronous in-memory cache first to avoid the
    // null → LaunchedEffect → recompose cycle.  Only fall back to the suspend path on cache miss.
    // When mxcUrl is null we use the local SVG so there is always a non-null URL.
    var avatarUrl by remember(mxcUrl, useCircleCache, effectiveUserId) {
        if (mxcUrl == null) {
            mutableStateOf(svgFallbackUri as String?)
        } else if (useCircleCache) {
            val cached = AvatarUtils.getAvatarUrlForRoomListCached(mxcUrl, homeserverUrl, effectiveUserId, effectiveDisplayName)
            mutableStateOf(cached)
        } else {
            mutableStateOf(AvatarUtils.getAvatarUrl(context, mxcUrl, homeserverUrl, effectiveUserId, effectiveDisplayName))
        }
    }

    // For RoomListScreen, resolve via suspend path only when the synchronous cache missed.
    LaunchedEffect(mxcUrl, useCircleCache) {
        if (useCircleCache && mxcUrl != null) {
            val resolved = AvatarUtils.getAvatarUrlForRoomList(context, mxcUrl, homeserverUrl, effectiveUserId, effectiveDisplayName)
            if (resolved != avatarUrl) {
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "AvatarImage[$effectiveUserId]: LaunchedEffect override mxc=$mxcUrl prev=$avatarUrl → resolved=$resolved"
                )
                avatarUrl = resolved
            } else if (BuildConfig.DEBUG) {
                Log.d(
                    "Andromuks",
                    "AvatarImage[$effectiveUserId]: LaunchedEffect no-op mxc=$mxcUrl resolved=$resolved (matches current)"
                )
            }
        }
    }
    
    // CRITICAL FIX: Use a stable key (userId or fallbackText) instead of avatarUrl for state persistence
    // This prevents state reset when avatarUrl is recalculated (even if it's the same value)
    // The stable key ensures state persists across room list updates (sync_complete recompositions).
    //
    // mxcUrl is included as a co-key: it only changes when the underlying media source changes
    // (e.g. roomState arrives and the avatar mxc flips from null → real, or admin sets a new
    // room avatar). A real source change must reset imageLoadFailed/imageHasLoaded — otherwise
    // a transient null mxcUrl (Coil cannot decode our data: SVG fallback → onError →
    // imageLoadFailed=true) latches the text fallback even after the real mxc arrives.
    val stableKey = userId ?: fallbackText

    // Track if the image failed to load - key by (stableKey, mxcUrl) so it resets on source change.
    var imageLoadFailed by remember(stableKey, mxcUrl) { mutableStateOf(false) }

    // PERFORMANCE: Track if image has successfully loaded - once loaded, show it even during fast scrolling
    var imageHasLoaded by remember(stableKey, mxcUrl) { mutableStateOf(false) }

    // CRITICAL FIX: Initialize shouldLoadImage based on avatarUrl, but persist state per stableKey
    // This prevents fallback flash: if avatarUrl exists, start with shouldLoadImage=true
    var shouldLoadImage by remember(stableKey, mxcUrl) {
        // Initialize to true if avatarUrl exists - AsyncImage handles cached images efficiently
        mutableStateOf(avatarUrl != null && isVisible)
    }
    
    // The CompositionLocal + caller flag used to suppress avatar loading entirely
    // during fast scrolling. That was the main cause of the "wall of avatars pops
    // in at once when scrolling stops" feel: rows that composed during fast scroll
    // would refuse to load, then ALL enable loading simultaneously when the flag
    // cleared. Coil's dispatcher already caps concurrent loads (maxRequests = 100
    // in ImageLoaderSingleton), so we don't need a second-order throttle here —
    // let Coil handle backpressure and start each row's request as soon as it
    // becomes visible. Suppression flags are still read for compatibility but no
    // longer block loading.
    val effectiveScrollingFast = LocalIsScrollingFast.current || isScrollingFast

    LaunchedEffect(isVisible, avatarUrl) {
        if (isVisible && avatarUrl != null) {
            shouldLoadImage = true
        }
        // Don't reset shouldLoadImage when item becomes invisible - keep it true
        // This ensures cached images show instantly when scrolling back into view
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
    val fallbackLetter = remember(displayName, userId, fallbackText) {
        if (userId != null) {
            AvatarUtils.getFallbackCharacter(displayName, userId)
        } else {
            fallbackText.take(1).uppercase()
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

    // Read state once so both the request and the callbacks share the same snapshot.
    val currentAvatarUrl = avatarUrl

    // Stable ImageRequest — only rebuilt when URL, auth token, or pixel size changes.
    // Never add scroll state or visibility flags here: that cancels in-flight Coil requests
    // on every scroll-speed transition (the same bug we fixed in MediaFunctions.kt).
    // crossfade(200) softens the swap from fallback-letter → bitmap; the swap itself is
    // unavoidable (Coil dispatches the request when AsyncImage composes), but a 200ms
    // fade makes it read as an intentional transition rather than a pop.
    val imageRequest = remember(currentAvatarUrl, targetPixelSize, mxcUrl) {
        currentAvatarUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .apply {
                    size(targetPixelSize)
                    // When we pass a local file path as the data string, Coil's FileKeyer
                    // computes the cache key via File.lastModified() — a 100-180 ms disk
                    // read on Main during composition (StrictMode flagged this). MXC URLs
                    // are content-addressed and immutable, so using them as explicit
                    // memory + disk cache keys bypasses the keyer entirely. Use the size
                    // suffix so two different render sizes don't collide in the memory
                    // cache (Coil stores each size as its own bitmap).
                    if (mxcUrl != null) {
                        // targetPixelSize is an Int (square px). Suffix it so two render
                        // sizes for the same mxc URL keep separate cache entries.
                        val key = "${mxcUrl}@${targetPixelSize}"
                        memoryCacheKey(key)
                        diskCacheKey(key)
                    }
                }
                .crossfade(200)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                // Background only for the text placeholder shown during fast scroll or SVG failure
                if (imageLoadFailed || (!imageHasLoaded && !shouldLoadImage)) {
                    Modifier.background(backgroundColor ?: MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            // Show text fallback if SVG itself failed, or image not yet loaded during fast scroll
            imageLoadFailed || !shouldLoadImage -> {
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
            else -> {
                if (currentAvatarUrl != null && shouldLoadImage && imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        imageLoader = imageLoader,
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        onSuccess = {
                            if (BuildConfig.DEBUG) Log.d(
                                "Andromuks",
                                "AvatarImage[$effectiveUserId]: onSuccess mxc=$mxcUrl url=$currentAvatarUrl"
                            )
                            imageLoadFailed = false
                            imageHasLoaded = true
                            if (useCircleCache && mxcUrl != null) {
                                AvatarUtils.updateResolvedUrl(mxcUrl, currentAvatarUrl)
                            }
                            if (useCircleCache && mxcUrl != null) {
                                coroutineScope.launch {
                                    val circleCached = CircleAvatarCache.getCachedFile(context, mxcUrl)
                                    if (circleCached == null && !currentAvatarUrl.contains("circle_avatar_cache")) {
                                        CircleAvatarCache.cacheCircularAvatar(
                                            context = context,
                                            mxcUrl = mxcUrl,
                                            sourceImageUrl = currentAvatarUrl,
                                            imageLoader = imageLoader,
                                        )
                                    }
                                }
                            }
                        },
                        onError = {
                            if (BuildConfig.DEBUG) Log.d(
                                "Andromuks",
                                "AvatarImage[$effectiveUserId]: onError mxc=$mxcUrl url=$currentAvatarUrl result=${it.result.throwable.javaClass.simpleName}: ${it.result.throwable.message}"
                            )
                            if (currentAvatarUrl.startsWith("data:")) {
                                if (BuildConfig.DEBUG) Log.d(
                                    "Andromuks",
                                    "AvatarImage[$effectiveUserId]: onError → data: failed, marking imageLoadFailed"
                                )
                                imageLoadFailed = true
                                return@AsyncImage
                            }
                            if (mxcUrl != null && !currentAvatarUrl.startsWith("http", ignoreCase = true)) {
                                AvatarUtils.removeResolvedUrl(mxcUrl)
                                val httpUrl = AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl, effectiveUserId, effectiveDisplayName)
                                if (httpUrl != null) {
                                    if (BuildConfig.DEBUG) Log.d(
                                        "Andromuks",
                                        "AvatarImage[$effectiveUserId]: onError → retrying with http url=$httpUrl (was non-http)"
                                    )
                                    avatarUrl = httpUrl
                                    shouldLoadImage = true
                                    return@AsyncImage
                                }
                            }
                            if (BuildConfig.DEBUG) Log.d(
                                "Andromuks",
                                "AvatarImage[$effectiveUserId]: onError → falling back to SVG (no http retry path)"
                            )
                            avatarUrl = svgFallbackUri
                            shouldLoadImage = true
                        }
                    )
                } else if (currentAvatarUrl != null && !imageHasLoaded) {
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
