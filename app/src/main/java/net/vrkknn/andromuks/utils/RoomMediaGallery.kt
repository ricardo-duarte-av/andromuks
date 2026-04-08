package net.vrkknn.andromuks.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MediaInfo
import net.vrkknn.andromuks.MediaMessage
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator

data class GalleryMediaItem(
    /** mxc:// URL of the full-size media – passed to ImageViewerDialog */
    val fullMxcUrl: String,
    /** mxc:// URL of the thumbnail if the event carried one, otherwise null */
    val thumbnailMxcUrl: String?,
    /** HTTP URL used by Coil to display the grid thumbnail */
    val thumbnailHttpUrl: String,
    val isVideo: Boolean,
    val timelineRowid: Long,
    val eventId: String
)

private fun extractMediaItems(
    events: List<TimelineEvent>,
    homeserverUrl: String
): List<GalleryMediaItem> {
    return events.mapNotNull { event ->
        if (event.timelineRowid <= 0) return@mapNotNull null

        val (content, isVideo) = when {
            event.type == "m.room.message" -> {
                val msgtype = event.content?.optString("msgtype") ?: return@mapNotNull null
                when (msgtype) {
                    "m.image" -> Pair(event.content, false)
                    "m.video" -> Pair(event.content, true)
                    else -> return@mapNotNull null
                }
            }
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> {
                val msgtype = event.decrypted?.optString("msgtype") ?: return@mapNotNull null
                when (msgtype) {
                    "m.image" -> Pair(event.decrypted, false)
                    "m.video" -> Pair(event.decrypted, true)
                    else -> return@mapNotNull null
                }
            }
            else -> return@mapNotNull null
        }

        val fullMxc = content?.optString("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val thumbnailMxc = content.optJSONObject("info")?.optString("thumbnail_url")?.takeIf { it.isNotBlank() }

        // For the grid thumbnail: prefer the event's own thumbnail_url; otherwise request a
        // server-side resize of the full media so we don't fetch huge originals.
        val thumbnailHttpUrl = if (thumbnailMxc != null) {
            MediaUtils.mxcToHttpUrl(thumbnailMxc, homeserverUrl)
        } else {
            MediaUtils.mxcToThumbnailUrl(fullMxc, homeserverUrl, width = 300, height = 300)
        } ?: return@mapNotNull null

        GalleryMediaItem(
            fullMxcUrl = fullMxc,
            thumbnailMxcUrl = thumbnailMxc,
            thumbnailHttpUrl = thumbnailHttpUrl,
            isVideo = isVideo,
            timelineRowid = event.timelineRowid,
            eventId = event.eventId
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
        blurHash = null,
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
        blurHash = null,
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

    val gridState = rememberLazyGridState()
    val homeserverUrl = appViewModel.homeserverUrl
    val authToken = appViewModel.authToken

    fun loadMore() {
        if (isLoadingMore || !hasMore) return
        isLoadingMore = true
        appViewModel.requestGalleryPaginate(
            roomId = roomId,
            maxTimelineId = nextMaxTimelineId,
            limit = 100
        ) { events, moreAvailable, minRowId ->
            val newItems = extractMediaItems(events, homeserverUrl)
            mediaItems = mediaItems + newItems
            hasMore = moreAvailable && events.isNotEmpty()
            if (minRowId > 0 && (nextMaxTimelineId == 0L || minRowId < nextMaxTimelineId)) {
                nextMaxTimelineId = minRowId
            } else if (events.isNotEmpty() && minRowId == 0L) {
                // All events lacked positive rowids – stop to avoid an infinite loop.
                hasMore = false
            }
            isLoadingMore = false
            isInitialLoading = false
        }
    }

    LaunchedEffect(roomId) {
        loadMore()
    }

    // Preemptive load: trigger when the user is within 20 items of the end of the list.
    val totalItems = mediaItems.size
    val lastVisibleIndex by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(lastVisibleIndex, totalItems, hasMore, isLoadingMore) {
        if (totalItems > 0 && lastVisibleIndex >= totalItems - 20 && hasMore && !isLoadingMore) {
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
                            imageVector = Icons.Filled.ArrowBack,
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
                        columns = GridCells.Fixed(4),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
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
                                onClick = { selectedItem = item }
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
                isEncrypted = false,
                shouldAutoPlay = true,
                onDismiss = { selectedItem = null }
            )
        } else {
            ImageViewerDialog(
                mediaMessage = item.toImageMediaMessage(),
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = false,
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
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.thumbnailHttpUrl)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .crossfade(true)
                .build(),
            contentDescription = if (item.isVideo) "Video thumbnail" else "Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (item.isVideo) {
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
