package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.ImageLoader
import net.vrkknn.andromuks.utils.MediaUtils



/**
 * Displays an image emoji in the emoji selection grid using Coil (supports GIFs).
 * 
 * @param mxcUrl The MXC URL for the emoji image
 * @param homeserverUrl The homeserver URL for MXC conversion
 * @param authToken The authentication token for MXC URL downloads
 */
@Composable
fun ImageEmoji(
    mxcUrl: String,
    homeserverUrl: String,
    authToken: String
) {
    val context = LocalContext.current
    
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ImageEmoji: Starting to load emoji from MXC URL: $mxcUrl")
    
    // Convert MXC URL to HTTP URL
    val httpUrl = remember(mxcUrl, homeserverUrl) {
        MediaUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
    }
    
    // Use shared ImageLoader singleton with custom User-Agent
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ImageEmoji: Converted HTTP URL: $httpUrl")
    
    if (httpUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(httpUrl)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            imageLoader = imageLoader,
            contentDescription = "Emoji",
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
            onSuccess = {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ImageEmoji: Successfully loaded emoji for $mxcUrl")
            },
            onError = { state ->
                if (state is coil.request.ErrorResult) {
                    CacheUtils.handleImageLoadError(
                        imageUrl = httpUrl,
                        throwable = state.throwable,
                        imageLoader = imageLoader,
                        context = "Emoji"
                    )
                }
            }
        )
    } else {
        android.util.Log.e("Andromuks", "ImageEmoji: Failed to convert MXC URL to HTTP URL: $mxcUrl")
        Text(
            text = "❌",
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

/**
 * Emoji selection dialog with categories and recent emojis
 */
@Composable
fun EmojiSelectionDialog(
    recentEmojis: List<String>,
    homeserverUrl: String,
    authToken: String,
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    
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
                .fillMaxHeight(0.75f), // About twice as tall, taking up most of the screen
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Category tabs
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(emojiCategories.size) { index ->
                        val category = emojiCategories[index]
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedCategory = index },
                            color = if (selectedCategory == index) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = category.icon,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selectedCategory == index) 
                                    MaterialTheme.colorScheme.onPrimary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                // Search box
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("Search / Custom Reaction") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    }
                )
                
                // Emoji grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val baseEmojis = if (selectedCategory == 0) {
                        recentEmojis
                    } else {
                        emojiCategories[selectedCategory].emojis
                    }
                    
                    // Filter emojis based on search text
                    // When searching, search across all categories for better results
                    val filteredEmojis = if (searchText.isBlank()) {
                        baseEmojis
                    } else {
                        // Search across all emojis when user types, not just current category
                        EmojiData.getAllEmojis().filter { it.contains(searchText, ignoreCase = true) }
                    }
                    
                    items(filteredEmojis) { emoji ->
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { 
                                    onEmojiSelected(emoji)
                                    onDismiss()
                                },
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (emoji.startsWith("mxc://")) {
                                    // Handle image reactions in recent tab
                                    ImageEmoji(emoji, homeserverUrl, authToken)
                                } else {
                                    // Handle regular emojis
                                    Text(
                                        text = emoji,
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                }
                            }
                        }
                    }
                    
                    // Custom reaction button if search text is not empty and not found in emojis
                    if (searchText.isNotBlank() && !baseEmojis.any { it.contains(searchText, ignoreCase = true) }) {
                        item(span = { GridItemSpan(8) }) {
                            Button(
                                onClick = { 
                                    onEmojiSelected(searchText)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                            ) {
                                Text("React with \"$searchText\"")
                            }
                        }
                    }
                }
            }
        }
    }
}

// Use comprehensive Unicode Standard Emoji dataset
private val emojiCategories = EmojiData.getEmojiCategories()

data class EmojiCategory(
    val icon: String,
    val name: String,
    val emojis: List<String>
)
