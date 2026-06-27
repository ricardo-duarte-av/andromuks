package net.vrkknn.andromuks.utils

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Spring
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.theme.scaledSpring
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animate
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MediaInfo
import net.vrkknn.andromuks.MediaMessage
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator

/**
 * Keep paginating until at least this many media items have been collected, so a freshly opened
 * gallery fills the screen (4-column grid) and becomes scrollable even when recent history holds
 * little or no media. Once exceeded, pagination is driven purely by scrolling.
 */
private const val GALLERY_PREFETCH_THRESHOLD = 40

/** Pinch-to-zoom column bounds for the gallery grid. */
private const val GALLERY_MIN_COLUMNS = 1
private const val GALLERY_MAX_COLUMNS = 10
private const val GALLERY_DEFAULT_COLUMNS = 4

data class GalleryMediaItem(
    /** mxc:// URL of the full-size media – passed to ImageViewerDialog */
    val fullMxcUrl: String,
    /** mxc:// URL of the thumbnail if the event carried one, otherwise null */
    val thumbnailMxcUrl: String?,
    /** Primary HTTP URL used by Coil to display the grid thumbnail */
    val thumbnailHttpUrl: String,
    /**
     * Fallback HTTP URL used if [thumbnailHttpUrl] fails to load.
     * Only set for m.image items that have no dedicated thumbnail mxc://: in that case
     * [thumbnailHttpUrl] asks the backend for a resized copy (`?thumbnail=avatar`) and
     * [fallbackHttpUrl] is the original full-size media URL.
     */
    val fallbackHttpUrl: String? = null,
    val isVideo: Boolean,
    /** True when the source event was m.room.encrypted — URLs carry ?encrypted=true */
    val isEncrypted: Boolean = false,
    val timelineRowid: Long,
    val eventId: String,
    /** BlurHash string from info.xyz.amorgan.blurhash (or info.blurhash), if present */
    val blurHash: String? = null
)

private fun extractMediaItems(
    events: List<TimelineEvent>,
    homeserverUrl: String
): List<GalleryMediaItem> {
    return events.mapNotNull { event ->
        if (event.timelineRowid == 0L) return@mapNotNull null

        val (content, isVideo, isEncrypted) = when {
            event.type == "m.room.message" -> {
                val msgtype = event.content?.optString("msgtype") ?: return@mapNotNull null
                when (msgtype) {
                    "m.image" -> Triple(event.content, false, false)
                    "m.video" -> Triple(event.content, true, false)
                    else -> return@mapNotNull null
                }
            }
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> {
                val msgtype = event.decrypted?.optString("msgtype") ?: return@mapNotNull null
                when (msgtype) {
                    "m.image" -> Triple(event.decrypted, false, true)
                    "m.video" -> Triple(event.decrypted, true, true)
                    else -> return@mapNotNull null
                }
            }
            else -> return@mapNotNull null
        }

        val fullMxc = content?.optString("url")?.takeIf { it.isNotBlank() }
            ?: content?.optJSONObject("file")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val info = content.optJSONObject("info")
        val thumbnailMxc = info?.optString("thumbnail_url")?.takeIf { it.isNotBlank() }
        val blurHash = info?.optString("xyz.amorgan.blurhash")?.takeIf { it.isNotBlank() }
            ?: info?.optString("blurhash")?.takeIf { it.isNotBlank() }

        // Build the thumbnail HTTP URL for the gallery grid.
        //
        // Priority:
        // 1. If the event carries a dedicated thumbnail mxc:// (thumbnail_url), use it directly.
        // 2. For m.image without one: append ?thumbnail=avatar to the full media HTTP URL so
        //    the backend produces a resized copy. Keep the full URL as a fallback for when that
        //    request fails (some media types the backend can't thumbnail).
        // 3. For m.video without one: keep the existing ?thumbnail=NxN server-side resize.
        val encryptedSuffix = if (isEncrypted) "encrypted=true" else null
        val fullHttpUrl = MediaUtils.mxcToHttpUrl(fullMxc, homeserverUrl) ?: return@mapNotNull null
        val fullHttpUrlWithEnc = if (encryptedSuffix != null) "$fullHttpUrl?$encryptedSuffix" else fullHttpUrl
        val thumbnailHttpUrl: String
        val fallbackHttpUrl: String?
        when {
            thumbnailMxc != null -> {
                val base = MediaUtils.mxcToHttpUrl(thumbnailMxc, homeserverUrl)
                    ?: return@mapNotNull null
                thumbnailHttpUrl = if (encryptedSuffix != null) "$base?$encryptedSuffix" else base
                fallbackHttpUrl = null
            }
            !isVideo -> {
                thumbnailHttpUrl = if (encryptedSuffix != null) {
                    "$fullHttpUrl?thumbnail=avatar&$encryptedSuffix"
                } else {
                    "$fullHttpUrl?thumbnail=avatar"
                }
                fallbackHttpUrl = fullHttpUrlWithEnc
            }
            else -> {
                val base = MediaUtils.mxcToThumbnailUrl(fullMxc, homeserverUrl, width = 300, height = 300)
                    ?: return@mapNotNull null
                thumbnailHttpUrl = if (encryptedSuffix != null) "$base&$encryptedSuffix" else base
                fallbackHttpUrl = null
            }
        }

        GalleryMediaItem(
            fullMxcUrl = fullMxc,
            thumbnailMxcUrl = thumbnailMxc,
            thumbnailHttpUrl = thumbnailHttpUrl,
            fallbackHttpUrl = fallbackHttpUrl,
            isVideo = isVideo,
            isEncrypted = isEncrypted,
            timelineRowid = event.timelineRowid,
            eventId = event.eventId,
            blurHash = blurHash
        )
    }
}

/** Build a [MediaMessage] for [ImageViewerDialog] (images only). */
private fun GalleryMediaItem.toImageMediaMessage(): MediaMessage = MediaMessage(
    url = fullMxcUrl,
    filename = fullMxcUrl.substringAfterLast("/"),
    caption = null,
    info = MediaInfo(
        width = 0,
        height = 0,
        size = 0L,
        mimeType = "image/jpeg",
        blurHash = blurHash,
        thumbnailUrl = thumbnailMxcUrl
    ),
    msgType = "m.image"
)

/** Build a [MediaMessage] for [VideoPlayerDialog] (videos only). */
private fun GalleryMediaItem.toVideoMediaMessage(): MediaMessage = MediaMessage(
    url = fullMxcUrl,
    filename = fullMxcUrl.substringAfterLast("/"),
    caption = null,
    info = MediaInfo(
        width = 0,
        height = 0,
        size = 0L,
        mimeType = "video/mp4",
        blurHash = blurHash,
        thumbnailUrl = thumbnailMxcUrl
    ),
    msgType = "m.video"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomMediaGalleryScreen(
    roomId: String,
    navController: NavController,
    appViewModel: AppViewModel
) {
    var mediaItems by remember { mutableStateOf<List<GalleryMediaItem>>(emptyList()) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    // Starts at 0 (= latest); updated to the lowest positive timelineRowid seen so far
    // so each subsequent request paginates further back in history.
    var nextMaxTimelineId by remember { mutableStateOf(0L) }

    var selectedItem by remember { mutableStateOf<GalleryMediaItem?>(null) }

    // Pinch-to-zoom column count: fewer columns = larger thumbnails, more columns = smaller.
    var columns by rememberSaveable { mutableStateOf(GALLERY_DEFAULT_COLUMNS) }

    val gridState = rememberLazyGridState()
    val homeserverUrl = appViewModel.homeserverUrl
    val authToken = appViewModel.authToken

    // Google-Photos-style continuous pinch zoom. During a pinch the whole grid scales under the
    // fingers (displayScale, anchored at zoomOrigin); on release we commit the nearest column count
    // and spring the residual scale back to 1f so the new layout settles in without a size jump.
    // displayScale is a plain Float (written synchronously from the restricted pointer scope, which
    // can't call suspending animation APIs); the release settle drives it via animate() off-scope.
    var displayScale by remember { mutableFloatStateOf(1f) }
    var zoomOrigin by remember { mutableStateOf(TransformOrigin.Center) }
    val zoomScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun loadMore() {
        if (net.vrkknn.andromuks.BuildConfig.DEBUG) android.util.Log.d("GalleryPaginate", "loadMore called: isLoadingMore=$isLoadingMore, hasMore=$hasMore, nextMaxTimelineId=$nextMaxTimelineId")
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        appViewModel.requestGalleryPaginate(
            roomId = roomId,
            maxTimelineId = nextMaxTimelineId,
            limit = 100 
        ) { events, moreAvailable, minRowId ->
            val newItems = extractMediaItems(events, homeserverUrl)
            if (net.vrkknn.andromuks.BuildConfig.DEBUG) android.util.Log.d("GalleryPaginate", "Callback: events=${events.size}, newItems=${newItems.size}, moreAvailable=$moreAvailable, minRowId=$minRowId")
            mediaItems = mediaItems + newItems
            hasMore = moreAvailable && events.isNotEmpty()
            if (minRowId != 0L && (nextMaxTimelineId == 0L || minRowId < nextMaxTimelineId)) {
                nextMaxTimelineId = minRowId
            } else if (events.isNotEmpty() && minRowId == 0L) {
                // All events lacked a valid timeline_rowid – stop to avoid an infinite loop.
                if (net.vrkknn.andromuks.BuildConfig.DEBUG) android.util.Log.d("GalleryPaginate", "Stopping to avoid infinite loop (all events lacked a valid timeline_rowid)")
                hasMore = false
            }
            if (net.vrkknn.andromuks.BuildConfig.DEBUG) android.util.Log.d("GalleryPaginate", "After state update: hasMore=$hasMore, nextMaxTimelineId=$nextMaxTimelineId")
            isLoadingMore = false
            isInitialLoading = false
        }
    }

    LaunchedEffect(roomId) {
        loadMore()
    }

    // Keep IntelligentMediaCache informed of which thumbnails are currently visible so it
    // can boost their priority and avoid evicting them under memory pressure.
    val visibleItemIndices by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }
    }
    LaunchedEffect(visibleItemIndices) {
        val visibleMxcUrls = visibleItemIndices
            .mapNotNull { idx -> mediaItems.getOrNull(idx) }
            .map { item -> item.thumbnailMxcUrl ?: item.fullMxcUrl }
            .toSet()
        IntelligentMediaCache.updateVisibility(visibleMxcUrls)
    }

    // Preemptive load: trigger when the user is within 20 items of the end of the list.
    val totalItems = mediaItems.size
    val lastVisibleIndex by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(lastVisibleIndex, totalItems, hasMore, isLoadingMore) {
        if (net.vrkknn.andromuks.BuildConfig.DEBUG) android.util.Log.d("GalleryPaginate", "Scroll check: lastVisible=$lastVisibleIndex, total=$totalItems, hasMore=$hasMore, isLoading=$isLoadingMore")
        // Two reasons to keep paginating:
        //  1. The grid hasn't filled the screen yet (totalItems too small to scroll). This also
        //     covers the case where a batch came back with zero media items (totalItems == 0):
        //     the user can't scroll an empty/short grid, so we must keep searching history back
        //     to the start until we find media or hasMore becomes false.
        //  2. The user scrolled within 20 items of the end of a populated grid.
        val notYetScrollable = totalItems < GALLERY_PREFETCH_THRESHOLD
        val scrolledNearEnd = totalItems > 0 && lastVisibleIndex >= totalItems - 20
        if ((notYetScrollable || scrolledNearEnd) && hasMore && !isLoadingMore) {
            if (net.vrkknn.andromuks.BuildConfig.DEBUG) android.util.Log.d("GalleryPaginate", "Load condition met (notYetScrollable=$notYetScrollable, scrolledNearEnd=$scrolledNearEnd) -> calling loadMore()")
            loadMore()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Gallery") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isInitialLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ExpressiveLoadingIndicator()
                    }
                }
                mediaItems.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No images or videos found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            // Pinch to zoom the column count, Google-Photos style. We only consume
                            // events once a second finger is down, so single-finger vertical
                            // scrolling still reaches the grid untouched. pointerInput is placed
                            // before graphicsLayer so the centroid/size it reads are in the grid's
                            // untransformed space.
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    // pinchTotal == 1.0 means "the layout as it is now"; >1 zoomed
                                    // in (fewer cols), <1 zoomed out (more cols).
                                    var pinchTotal = 1f
                                    var pinching = false
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        if (pressed >= 2) {
                                            if (!pinching) {
                                                pinching = true
                                                settleJob?.cancel() // grab control mid-settle
                                            }
                                            // Clamp so the live scale can't run past the column
                                            // bounds: projected columns = columns / pinchTotal must
                                            // stay within [MIN, MAX].
                                            pinchTotal = (pinchTotal * event.calculateZoom()).coerceIn(
                                                columns.toFloat() / GALLERY_MAX_COLUMNS,
                                                columns.toFloat() / GALLERY_MIN_COLUMNS
                                            )
                                            if (size.width > 0 && size.height > 0) {
                                                val centroid = event.calculateCentroid(useCurrent = true)
                                                zoomOrigin = TransformOrigin(
                                                    (centroid.x / size.width).coerceIn(0f, 1f),
                                                    (centroid.y / size.height).coerceIn(0f, 1f)
                                                )
                                            }
                                            // Plain state write — allowed in the restricted pointer
                                            // scope. settleJob was cancelled above so nothing fights
                                            // these live updates.
                                            displayScale = pinchTotal
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    if (pinching) {
                                        // Commit the nearest column count and settle the residual
                                        // scale to 1f, keeping cell size continuous at the swap.
                                        val targetCols = (columns / pinchTotal).roundToInt()
                                            .coerceIn(GALLERY_MIN_COLUMNS, GALLERY_MAX_COLUMNS)
                                        settleJob = zoomScope.launch {
                                            if (targetCols != columns) {
                                                // Keep cell size continuous across the swap, then
                                                // settle to 1f.
                                                displayScale = displayScale * targetCols / columns
                                                columns = targetCols
                                            }
                                            animate(
                                                initialValue = displayScale,
                                                targetValue = 1f,
                                                animationSpec = scaledSpring(stiffness = Spring.StiffnessMediumLow)
                                            ) { value, _ -> displayScale = value }
                                        }
                                    }
                                }
                            }
                            .graphicsLayer {
                                scaleX = displayScale
                                scaleY = displayScale
                                transformOrigin = zoomOrigin
                                clip = true // keep the scaled grid inside its own bounds
                            },
                        contentPadding = PaddingValues(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = mediaItems,
                            key = { _, item -> item.eventId }
                        ) { _, item ->
                            GalleryThumbnail(
                                item = item,
                                authToken = authToken,
                                onClick = { selectedItem = item },
                                // Glide each thumbnail to its new cell when a pinch changes the
                                // column count, instead of snapping the whole grid instantly.
                                modifier = Modifier.animateItem(
                                    placementSpec = scaledSpring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        visibilityThreshold = IntOffset(1, 1)
                                    )
                                )
                            )
                        }
                        if (isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Full-screen viewer: image viewer for images, video player for videos
    selectedItem?.let { item ->
        if (item.isVideo) {
            VideoPlayerDialog(
                mediaMessage = item.toVideoMediaMessage(),
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = item.isEncrypted,
                shouldAutoPlay = true,
                onDismiss = { selectedItem = null }
            )
        } else {
            ImageViewerDialog(
                mediaMessage = item.toImageMediaMessage(),
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = item.isEncrypted,
                sourceBounds = null,
                onDismiss = { selectedItem = null }
            )
        }
    }
}

@Composable
private fun GalleryThumbnail(
    item: GalleryMediaItem,
    authToken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = remember { net.vrkknn.andromuks.utils.ImageLoaderSingleton.get(context) }
    val shape = RoundedCornerShape(8.dp)

    // Prefer a cached file on disk (IntelligentMediaCache) to avoid re-downloading thumbnails.
    // The cache key is the thumbnail mxc:// URL when the event carried one, otherwise the full
    // media URL (which the server will resize on-the-fly via the thumbnail HTTP URL).
    val thumbnailMxcKey = item.thumbnailMxcUrl ?: item.fullMxcUrl
    var cachedFile by remember(thumbnailMxcKey) { mutableStateOf<File?>(null) }
    LaunchedEffect(thumbnailMxcKey) {
        cachedFile = IntelligentMediaCache.getCachedFile(context, thumbnailMxcKey)
    }

    // If the backend-generated thumbnail fails (only possible for the ?thumbnail=avatar path),
    // retry once with the full media URL.
    var thumbnailFailed by remember(thumbnailMxcKey) { mutableStateOf(false) }
    // Set to true when even the fallback fails — media is unavailable on the server.
    var mediaUnavailable by remember(thumbnailMxcKey) { mutableStateOf(false) }

    // If the cache returned a file, hand its absolute path to Coil directly (no auth header
    // needed for local files). Otherwise use the thumbnail URL, or fall back to the full URL
    // if the thumbnail request already failed.
    val displayData: Any = remember(thumbnailMxcKey, cachedFile, thumbnailFailed) {
        when {
            cachedFile != null -> cachedFile!!.absolutePath
            thumbnailFailed && item.fallbackHttpUrl != null -> item.fallbackHttpUrl
            else -> item.thumbnailHttpUrl
        }
    }

    // Decode BlurHash asynchronously (can be slow for large hashes — must not run on Main).
    // Used as a placeholder that "focuses in" to the real thumbnail as Coil crossfades over it.
    var blurHashBitmap by remember(item.blurHash) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.blurHash) {
        if (item.blurHash != null) {
            blurHashBitmap = withContext(Dispatchers.Default) {
                BlurHashUtils.decodeBlurHash(item.blurHash, width = 32, height = 32)
                    ?.asImageBitmap()
            }
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // BlurHash layer — visible immediately, replaced by the real image once loaded.
        blurHashBitmap?.let { bitmap ->
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(displayData)
                .crossfade(300)
                .build(),
            imageLoader = imageLoader,
            onError = {
                if (!thumbnailFailed && item.fallbackHttpUrl != null) {
                    // First failure on the ?thumbnail=avatar path — retry with full URL.
                    thumbnailFailed = true
                } else {
                    // No fallback available, or fallback also failed — media is gone.
                    mediaUnavailable = true
                }
            },
            contentDescription = if (item.isVideo) "Video thumbnail" else "Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (mediaUnavailable) {
            Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = "Media unavailable",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        } else if (item.isVideo) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.Black.copy(alpha = 0.55f), shape = androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
