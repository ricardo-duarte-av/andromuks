package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.EmojiPacksCache
import net.vrkknn.andromuks.StickerPackCoordinator
import net.vrkknn.andromuks.StickerPacksCache

/**
 * The packs a room hosts, with subscribe / remove per pack.
 *
 * This exists because the picker is not a reachable entry point everywhere. Sticker rooms are
 * routinely configured so ordinary members cannot post, and the picker buttons hang off the
 * composer — `isInputEnabled`, which is false without `canSendMessage`. In exactly the rooms most
 * worth subscribing from, the in-picker `+` is unreachable. Room info has no such gate.
 *
 * Unlike the picker's trailing tabs this lists packs the account is *already* subscribed to as
 * well, so the room that hosts a pack is also a place to drop it.
 */
@Composable
internal fun RoomStickerPacksDialog(
    roomId: String,
    homeserverUrl: String,
    authToken: String,
    packsProvider: () -> List<StickerPackCoordinator.RoomPack>,
    onToggleSubscription: (StickerPackCoordinator.RoomPack) -> Unit,
    onDismiss: () -> Unit,
) {
    // Subscribe/unsubscribe writes account data, which is not Compose state; the cache versions
    // cover pack data arriving from the server after a subscribe.
    var refreshTick by remember { mutableIntStateOf(0) }
    val stickerVersion = StickerPacksCache.version
    val emojiVersion = EmojiPacksCache.version

    val packs = remember(roomId, refreshTick, stickerVersion, emojiVersion) { packsProvider() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Packs in this room") },
        text = {
            if (packs.isEmpty()) {
                Text(
                    text = "This room hosts no sticker or emoji packs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(packs, key = { it.packName }) { pack ->
                        RoomPackRow(
                            pack = pack,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onToggle = {
                                onToggleSubscription(pack)
                                refreshTick++
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun RoomPackRow(pack: StickerPackCoordinator.RoomPack, homeserverUrl: String, authToken: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val thumbnail = pack.thumbnailMxc
            if (thumbnail != null) {
                StickerImage(mxcUrl = thumbnail, homeserverUrl = homeserverUrl, authToken = authToken)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pack.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = packCountsLabel(pack),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(onClick = onToggle) {
            Text(if (pack.subscribed) "Remove" else "Add")
        }
    }
}

/** "12 stickers · 30 emojis" — both halves, since a pack can feed either picker. */
private fun packCountsLabel(pack: StickerPackCoordinator.RoomPack): String {
    val parts = buildList {
        if (pack.stickerCount > 0) add("${pack.stickerCount} stickers")
        if (pack.emojiCount > 0) add("${pack.emojiCount} emojis")
    }
    return parts.joinToString(" · ")
}
