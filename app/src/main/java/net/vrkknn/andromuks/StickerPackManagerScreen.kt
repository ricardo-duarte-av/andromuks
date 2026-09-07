package net.vrkknn.andromuks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import net.vrkknn.andromuks.utils.StickerImage

/**
 * Sticker & emoji pack subscriptions.
 *
 * Lists what this account is subscribed to (the `rooms` map of `im.ponies.emote_rooms` /
 * `m.image_pack.rooms`) and lets each entry be removed. Adding is deliberately *not* here:
 * discovery happens in the pickers, in the room that hosts the pack, where the stickers can
 * actually be seen before subscribing.
 *
 * A subscribed pack whose data never arrived is rendered as "not loaded" rather than omitted — the
 * subscription is real even when the pack behind it failed to fetch, and hiding it would make that
 * failure invisible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPackManagerScreen(appViewModel: AppViewModel, navController: NavController, modifier: Modifier = Modifier) {
    // Bumped by subscribe/unsubscribe on this screen; the cache versions cover packs arriving from
    // the server while the screen is open.
    var refreshTick by remember { mutableIntStateOf(0) }
    var pendingRemoval by remember { mutableStateOf<StickerPackCoordinator.PackRef?>(null) }

    val stickerVersion = StickerPacksCache.version
    val emojiVersion = EmojiPacksCache.version

    val entries = remember(refreshTick, stickerVersion, emojiVersion) {
        appViewModel.stickerPackCoordinator.subscribedPacks().map { ref ->
            val stickerPack = appViewModel.stickerPacks.firstOrNull {
                it.roomId == ref.roomId && it.packName == ref.packName
            }
            val emojiPack = appViewModel.customEmojiPacks.firstOrNull {
                it.roomId == ref.roomId && it.packName == ref.packName
            }
            SubscribedPackEntry(
                ref = ref,
                displayName = stickerPack?.displayName ?: emojiPack?.displayName ?: ref.packName,
                roomName = appViewModel.getRoomById(ref.roomId)?.name ?: ref.roomId,
                stickerCount = stickerPack?.stickers?.size ?: 0,
                emojiCount = emojiPack?.emojis?.size ?: 0,
                thumbnailMxc = stickerPack?.stickers?.firstOrNull()?.mxcUrl
                    ?: emojiPack?.emojis?.firstOrNull()?.mxcUrl,
                loaded = stickerPack != null || emojiPack != null,
            )
        }
    }

    val removalTarget = pendingRemoval
    if (removalTarget != null) {
        val name = entries.firstOrNull { it.ref == removalTarget }?.displayName ?: removalTarget.packName
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove $name?") },
            text = {
                Text(
                    "This pack's stickers and emojis will no longer be offered on any of your devices. " +
                        "You can add it again from the picker in the room that hosts it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        appViewModel.stickerPackCoordinator.unsubscribe(
                            removalTarget.roomId,
                            removalTarget.packName,
                        )
                        pendingRemoval = null
                        refreshTick++
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sticker & Emoji Packs") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No packs subscribed.\n\nOpen the sticker or emoji picker in a room that " +
                        "hosts packs to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries, key = { "${it.ref.roomId}|${it.ref.packName}" }) { entry ->
                    SubscribedPackCard(
                        entry = entry,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        onRemove = { pendingRemoval = entry.ref },
                    )
                }
            }
        }
    }
}

/** One row of [StickerPackManagerScreen], flattened for rendering. */
private data class SubscribedPackEntry(
    val ref: StickerPackCoordinator.PackRef,
    val displayName: String,
    val roomName: String,
    val stickerCount: Int,
    val emojiCount: Int,
    val thumbnailMxc: String?,
    val loaded: Boolean,
)

@Composable
private fun SubscribedPackCard(entry: SubscribedPackEntry, homeserverUrl: String, authToken: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                val thumbnail = entry.thumbnailMxc
                if (thumbnail != null) {
                    StickerImage(
                        mxcUrl = thumbnail,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.roomName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = packContentsLabel(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.loaded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove ${entry.displayName}",
                )
            }
        }
    }
}

/** "12 stickers · 30 emojis", or why there is nothing to count. */
private fun packContentsLabel(entry: SubscribedPackEntry): String {
    if (!entry.loaded) return "Not loaded — the pack was not found in its room"
    val parts = buildList {
        if (entry.stickerCount > 0) add("${entry.stickerCount} stickers")
        if (entry.emojiCount > 0) add("${entry.emojiCount} emojis")
    }
    return if (parts.isEmpty()) "Empty pack" else parts.joinToString(" · ")
}
