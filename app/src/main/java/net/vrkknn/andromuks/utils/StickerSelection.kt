package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy

/**
 * Sticker selection dialog with sticker packs
 */
@Composable
fun StickerSelectionDialog(
    homeserverUrl: String,
    authToken: String,
    onStickerSelected: (net.vrkknn.andromuks.AppViewModel.Sticker) -> Unit,
    onDismiss: () -> Unit,
    stickerPacks: List<net.vrkknn.andromuks.AppViewModel.StickerPack> = emptyList()
) {
    var selectedPackIndex by remember { mutableStateOf(0) }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header with close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Stickers",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }
                
                // Pack tabs
                if (stickerPacks.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(stickerPacks.size) { index ->
                            val pack = stickerPacks[index]
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedPackIndex = index },
                                color = if (selectedPackIndex == index) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = pack.displayName,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (selectedPackIndex == index) 
                                        MaterialTheme.colorScheme.onPrimary 
                                    else 
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                // Sticker grid
                if (stickerPacks.isNotEmpty() && selectedPackIndex < stickerPacks.size) {
                    val selectedPack = stickerPacks[selectedPackIndex]
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gridItems(selectedPack.stickers) { sticker ->
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onStickerSelected(sticker)
                                        onDismiss()
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Sticker image
                                Surface(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    color = Color.Transparent
                                ) {
                                    StickerImage(
                                        mxcUrl = sticker.mxcUrl,
                                        homeserverUrl = homeserverUrl,
                                        authToken = authToken
                                    )
                                }
                                
                                // Sticker caption
                                Text(
                                    text = sticker.body ?: sticker.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    // No stickers available
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No stickers available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays a sticker image using Coil
 */
@Composable
fun StickerImage(
    mxcUrl: String,
    homeserverUrl: String,
    authToken: String
) {
    val context = LocalContext.current
    
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "StickerImage: Starting to load sticker from MXC URL: $mxcUrl")
    
    // Convert MXC URL to HTTP URL
    val httpUrl = remember(mxcUrl, homeserverUrl) {
        MediaUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
    }
    
    // Use shared ImageLoader singleton
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "StickerImage: Converted HTTP URL: $httpUrl")
    
    if (httpUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(httpUrl)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            imageLoader = imageLoader,
            contentDescription = "Sticker",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onSuccess = {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "StickerImage: Successfully loaded sticker for $mxcUrl")
            },
            onError = { state ->
                if (state is coil.request.ErrorResult) {
                    CacheUtils.handleImageLoadError(
                        imageUrl = httpUrl,
                        throwable = state.throwable,
                        imageLoader = imageLoader,
                        context = "Sticker"
                    )
                }
            }
        )
    } else {
        android.util.Log.e("Andromuks", "StickerImage: Failed to convert MXC URL to HTTP URL: $mxcUrl")
        Text(
            text = "❌",
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

