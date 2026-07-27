package net.vrkknn.andromuks.utils

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MediaMessage
import net.vrkknn.andromuks.PowerLevelsInfo
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.formatTimestamp
import org.json.JSONObject

// MSC4274 inline media galleries — parsing and timeline rendering.
//
// See docs/GALLERIES.md for the event shape and the design of the mosaic. Sending galleries is not
// implemented; this file is receive-only.

/** Stable and unstable msgtypes for an MSC4274 gallery. */
val GALLERY_MSGTYPES = setOf("m.gallery", "dm.filament.gallery")

/** Tiles beyond this are collapsed into a `+N` overlay on the last visible tile. */
private const val GALLERY_MAX_TILES = 9

/** Gap between mosaic tiles. */
private val GALLERY_TILE_GAP = 2.dp

/** Corner radius of the mosaic as a whole — individual tiles are square. */
private val GALLERY_MOSAIC_CORNER = 10.dp

/**
 * One renderable entry of a gallery. Encryption is per item: each `itemtypes` entry carries either a
 * plain `url` or an encrypted `file`, so the flag travels with the item rather than the event.
 */
@Immutable
data class GalleryItem(val media: MediaMessage, val isEncrypted: Boolean)

/**
 * A parsed MSC4274 gallery.
 *
 * @param items the visual items (`m.image` / `m.video`), in event order
 * @param caption the gallery-level caption (`content.body`), null when blank
 * @param otherAttachmentCount how many `m.audio` / `m.file` items were dropped. v1 does not render
 *   them (see docs/GALLERIES.md); the count is surfaced as a `+N other attachments` line so they are
 *   never silently lost.
 */
@Immutable
data class GalleryMessage(val items: List<GalleryItem>, val caption: String?, val otherAttachmentCount: Int)

/**
 * Parses an MSC4274 gallery out of a message content object.
 *
 * Items reuse [parseMediaMessage] with `typeKey = "itemtype"` — a gallery item has the same schema as
 * a standalone media content, only the type key differs. Items carry no caption of their own (their
 * `body` is the filename), so null is passed for their local content.
 *
 * @return null when there is no non-empty `itemtypes` array, i.e. the content is not a gallery (or is
 *   an empty one) — callers fall back to rendering the body as plain text
 */
fun parseGalleryMessage(content: JSONObject?, localContent: JSONObject?): GalleryMessage? {
    val itemtypes = content?.optJSONArray("itemtypes") ?: return null
    if (itemtypes.length() == 0) return null

    val parsed = (0 until itemtypes.length())
        .mapNotNull { itemtypes.optJSONObject(it) }
        .mapNotNull { itemJson ->
            parseMediaMessage(
                content = itemJson,
                body = itemJson.optString("body", ""),
                typeKey = "itemtype",
            )?.let { itemJson to it }
        }
    val (visual, other) = parsed.partition { (_, media) ->
        media.msgType == "m.image" || media.msgType == "m.video"
    }
    val items = visual.map { (itemJson, media) ->
        GalleryItem(media = media, isEncrypted = mediaContentHasEncryptedFile(itemJson))
    }
    val otherAttachmentCount = other.size
    if (items.isEmpty() && otherAttachmentCount == 0) return null

    // The caption is the gallery's own body. sanitized_html is preferred when gomuks produced it,
    // matching how single media captions resolve.
    val sanitizedHtml = localContent?.optString("sanitized_html")?.takeIf { it.isNotBlank() }
    val caption = (sanitizedHtml ?: content.optString("body", "")).takeIf { it.isNotBlank() }

    return GalleryMessage(items = items, caption = caption, otherAttachmentCount = otherAttachmentCount)
}

/**
 * Renders a gallery inside a message bubble: the mosaic, then the caption, then the timestamp —
 * mirroring the layout [MediaMessage] uses for a captioned single image.
 *
 * Tapping a tile opens that item: images in [ImageViewerDialog], videos in [VideoPlayerDialog].
 * Long-pressing a tile raises the message menu, as it does for single media.
 */
@Composable
fun GalleryMessageBubble(
    gallery: GalleryMessage,
    homeserverUrl: String,
    authToken: String,
    isMine: Boolean,
    modifier: Modifier = Modifier,
    event: TimelineEvent? = null,
    timestamp: Long? = null,
    isConsecutive: Boolean = false,
    editedBy: TimelineEvent? = null,
    onReply: () -> Unit = {},
    onReact: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    myUserId: String? = null,
    powerLevels: PowerLevelsInfo? = null,
    appViewModel: AppViewModel? = null,
    onBubbleClick: (() -> Unit)? = null,
    onShowEditHistory: (() -> Unit)? = null,
    onShowMenu: ((MessageMenuConfig) -> Unit)? = null,
    onShowReactions: (() -> Unit)? = null,
    bubbleColorOverride: Color? = null,
    hasBeenEditedOverride: Boolean? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isThreadMessage = event?.isThreadMessage() ?: false
    val hasBeenEdited = hasBeenEditedOverride ?: remember(event?.eventId, appViewModel?.timelineUpdateCounter) {
        event?.let { appViewModel?.isMessageEdited(it.eventId) ?: false } ?: false
    }
    val bubbleColors = remember(colorScheme, isMine, isThreadMessage, hasBeenEdited) {
        BubblePalette.colors(
            colorScheme = colorScheme,
            isMine = isMine,
            isEdited = hasBeenEdited,
            isThreadMessage = isThreadMessage,
        )
    }
    val bubbleColor = bubbleColorOverride ?: bubbleColors.container
    val bubbleShape = RoundedCornerShape(
        topStart = if (isMine) 12.dp else 4.dp,
        topEnd = if (isMine) 4.dp else 12.dp,
        bottomStart = 12.dp,
        bottomEnd = 12.dp,
    )

    // Viewer state: which item is open, and the tile bounds it should animate out of.
    var openItemIndex by remember(event?.eventId) { mutableStateOf<Int?>(null) }
    var openItemBounds by remember(event?.eventId) { mutableStateOf<Rect?>(null) }
    // Incremented by a tile long-press to raise the bubble's message menu.
    var triggerMenuFromTile by remember { mutableIntStateOf(0) }

    val openItem = openItemIndex?.let { gallery.items.getOrNull(it) }
    if (openItem != null) {
        if (openItem.media.msgType == "m.video") {
            VideoPlayerDialog(
                mediaMessage = openItem.media,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                isEncrypted = openItem.isEncrypted,
                onDismiss = {
                    openItemIndex = null
                    openItemBounds = null
                },
            )
        } else {
            // Only images page: a video in the middle of the gallery would have nothing to show in
            // the pager, so the viewer gets the image items and the tapped one's index among them.
            val imageItems = remember(gallery.items) { gallery.items.filter { it.media.msgType != "m.video" } }
            val initialIndex = remember(imageItems, openItem) { imageItems.indexOf(openItem).coerceAtLeast(0) }
            ImageViewerDialog(
                items = imageItems,
                initialIndex = initialIndex,
                homeserverUrl = homeserverUrl,
                authToken = authToken,
                sourceBounds = openItemBounds,
                onDismiss = {
                    openItemIndex = null
                    openItemBounds = null
                },
            )
        }
    }

    val body: @Composable () -> Unit = {
        Column {
            GalleryMosaic(
                items = gallery.items,
                homeserverUrl = homeserverUrl,
                appViewModel = appViewModel,
                roomId = event?.roomId,
                onTileClick = { index, bounds ->
                    openItemBounds = bounds
                    openItemIndex = index
                },
                onTileLongPress = { triggerMenuFromTile++ },
            )

            if (gallery.caption != null) {
                GalleryCaption(
                    caption = gallery.caption,
                    event = event,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    onUserClick = onUserClick,
                    appViewModel = appViewModel,
                )
            }

            if (gallery.otherAttachmentCount > 0) {
                // v1 renders images and videos only; audio/file items are surfaced as a count so
                // they are visible even though they are not playable here yet.
                Text(
                    text = "+${gallery.otherAttachmentCount} other attachments",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (timestamp != null && isConsecutive) {
                GalleryBubbleTimestamp(
                    timestamp = timestamp,
                    edited = editedBy != null,
                    isMine = isMine,
                    onEditedClick = onShowEditHistory,
                )
            }
        }
    }

    if (event != null) {
        MessageBubbleWithMenu(
            event = event,
            bubbleColor = bubbleColor,
            bubbleShape = bubbleShape,
            modifier = modifier.wrapContentWidth(),
            isMine = isMine,
            myUserId = myUserId,
            powerLevels = powerLevels,
            onReply = onReply,
            onReact = onReact,
            onEdit = onEdit,
            onDelete = onDelete,
            appViewModel = appViewModel,
            onBubbleClick = onBubbleClick,
            onShowEditHistory = onShowEditHistory,
            externalMenuTrigger = triggerMenuFromTile,
            mentionBorder = bubbleColors.mentionBorder,
            threadBorder = bubbleColors.threadBorder,
            onShowMenu = onShowMenu,
            onShowReactions = onShowReactions,
        ) {
            body()
        }
    } else {
        Surface(
            modifier = modifier.wrapContentWidth(),
            shape = bubbleShape,
            color = bubbleColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            body()
        }
    }
}

/**
 * The mosaic itself. Tile arrangement is a pure function of the item count (see docs/GALLERIES.md),
 * so the height is known without measuring — which keeps timeline scroll anchoring stable.
 *
 * Callers handle the single-item case before reaching here: a one-item gallery renders as a plain
 * image instead.
 */
@Composable
private fun GalleryMosaic(
    items: List<GalleryItem>,
    homeserverUrl: String,
    appViewModel: AppViewModel?,
    roomId: String?,
    onTileClick: (Int, Rect?) -> Unit,
    onTileLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frameWidth = STANDARD_MEDIA_WIDTH
    val gap = GALLERY_TILE_GAP
    // Preview gating applies to the mosaic as a whole: one tap reveals every tile.
    val renderThumbnailsAlways = appViewModel?.resolveShowMediaPreviews(roomId) ?: true
    var isRevealed by remember(items) { mutableStateOf(false) }
    val revealed = renderThumbnailsAlways || isRevealed

    val tile: @Composable (Int, Dp, Dp, Boolean) -> Unit = { index, tileWidth, tileHeight, isOverflowTile ->
        GalleryTile(
            item = items[index],
            homeserverUrl = homeserverUrl,
            revealed = revealed,
            overflowCount = if (isOverflowTile) items.size - GALLERY_MAX_TILES else 0,
            onClick = { bounds ->
                if (!revealed) isRevealed = true else onTileClick(index, bounds)
            },
            onLongPress = onTileLongPress,
            modifier = Modifier
                .width(tileWidth)
                .height(tileHeight),
        )
    }

    Column(modifier = modifier.padding(2.dp).clip(RoundedCornerShape(GALLERY_MOSAIC_CORNER))) {
        when (items.size) {
            2 -> {
                val side = (frameWidth - gap) / 2
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tile(0, side, side, false)
                    tile(1, side, side, false)
                }
            }

            3 -> {
                // One large tile on the left, two stacked on the right.
                val largeSide = (frameWidth - gap) * 2 / 3
                val smallWidth = frameWidth - gap - largeSide
                val smallHeight = (largeSide - gap) / 2
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tile(0, largeSide, largeSide, false)
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        tile(1, smallWidth, smallHeight, false)
                        tile(2, smallWidth, smallHeight, false)
                    }
                }
            }

            else -> {
                val columns = if (items.size == 4) 2 else 3
                val side = (frameWidth - gap * (columns - 1)) / columns
                val visibleCount = minOf(items.size, GALLERY_MAX_TILES)
                val lastVisibleIndex = visibleCount - 1
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (rowStart in 0 until visibleCount step columns) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (index in rowStart until minOf(rowStart + columns, visibleCount)) {
                                tile(index, side, side, index == lastVisibleIndex && items.size > GALLERY_MAX_TILES)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single mosaic tile.
 *
 * Loads the pre-baked thumbnail — tiles are at most a third of [STANDARD_MEDIA_WIDTH], so the
 * thumbnail is always the right asset — and falls back to the original if the thumbnail fails, the
 * same ladder single media uses. The item's own BlurHash sits underneath while loading.
 */
@Composable
private fun GalleryTile(
    item: GalleryItem,
    homeserverUrl: String,
    revealed: Boolean,
    overflowCount: Int,
    onClick: (Rect?) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    val media = item.media

    // Thumbnail first; flipped to the original when the thumbnail fails to load.
    var useOriginal by remember(media.url) { mutableStateOf(media.info.thumbnailUrl.isNullOrBlank()) }
    val displayMxcUrl = if (useOriginal) media.url else media.info.thumbnailUrl!!
    val displayIsEncrypted = if (useOriginal) item.isEncrypted else media.info.thumbnailIsEncrypted

    val imageUrl = remember(displayMxcUrl, displayIsEncrypted, homeserverUrl) {
        val targetHttp = if (useOriginal) {
            MediaUtils.mxcToHttpUrl(displayMxcUrl, homeserverUrl, registerMapping = false)
        } else {
            MediaUtils.mxcToThumbnailUrl(displayMxcUrl, homeserverUrl, registerMapping = false)
        } ?: MediaUtils.mxcToHttpUrl(media.url, homeserverUrl, registerMapping = false)
        if (displayIsEncrypted && targetHttp != null) {
            val separator = if (targetHttp.contains("?")) "&" else "?"
            "$targetHttp${separator}encrypted=true"
        } else {
            targetHttp
        }
    }

    LaunchedEffect(imageUrl, displayMxcUrl) {
        val url = imageUrl ?: return@LaunchedEffect
        CoilUrlMapper.registerMapping(url, displayMxcUrl)
    }

    val blurHash = media.info.thumbnailBlurHash ?: media.info.blurHash
    var blurHashBitmap by remember(blurHash) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(blurHash) {
        if (blurHash == null) return@LaunchedEffect
        blurHashBitmap = withContext(Dispatchers.Default) {
            BlurHashUtils.decodeBlurHash(blurHash, 32, 32)?.asImageBitmap()
        }
    }

    var tileBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords -> tileBounds = coords.boundsInWindow() }
            .combinedClickable(
                onClick = { onClick(tileBounds) },
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val blurHashBitmapSnapshot = blurHashBitmap
        if (blurHashBitmapSnapshot != null) {
            Image(
                painter = BitmapPainter(blurHashBitmapSnapshot),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        if (revealed) {
            val imageRequest = remember(imageUrl, useOriginal) {
                ImageRequest.Builder(context)
                    .data(imageUrl ?: "")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(200)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                imageLoader = imageLoader,
                contentDescription = media.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    // Thumbnail unavailable — retry with the original, matching single-media behaviour.
                    if (!useOriginal) useOriginal = true
                },
            )
        } else {
            Text(
                text = "Tap to show",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        if (media.msgType == "m.video" && revealed) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 2.dp),
                )
            }
        }

        if (overflowCount > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflowCount",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * The gallery caption, rendered with HTML when the event carries it (links, mentions, formatting) and
 * as plain text otherwise.
 */
@Composable
private fun GalleryCaption(
    caption: String,
    event: TimelineEvent?,
    homeserverUrl: String,
    authToken: String,
    onUserClick: (String) -> Unit,
    appViewModel: AppViewModel?,
) {
    val supportsHtml = event != null && supportsHtmlRendering(event)
    if (supportsHtml) {
        HtmlMessageText(
            event = event,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            onMatrixUserClick = onUserClick,
            appViewModel = appViewModel,
        )
    } else {
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** In-bubble timestamp for consecutive messages, matching the single-media bubble. */
@Composable
private fun GalleryBubbleTimestamp(timestamp: Long, edited: Boolean, isMine: Boolean, onEditedClick: (() -> Unit)?) {
    val text = if (edited) "${formatTimestamp(timestamp)} (edited)" else formatTimestamp(timestamp)
    val base = Modifier
        .wrapContentWidth(if (isMine) Alignment.Start else Alignment.End)
        .padding(horizontal = 12.dp, vertical = 4.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = if (edited && onEditedClick != null) base.clickable { onEditedClick() } else base,
    )
}
