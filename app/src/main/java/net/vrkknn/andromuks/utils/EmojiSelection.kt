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
    onDismiss: () -> Unit,
    customEmojiPacks: List<net.vrkknn.andromuks.AppViewModel.EmojiPack> = emptyList()
) {
    // Calculate total categories: 1 (recent) + emojiCategories.size + customEmojiPacks.size
    val totalCategories = 1 + emojiCategories.size + customEmojiPacks.size
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
                    // Recent tab (index 0) - only show if there are recent emojis or always show for consistency
                    item(key = "recent") {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedCategory = 0 },
                            color = if (selectedCategory == 0) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "🕒",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selectedCategory == 0) 
                                    MaterialTheme.colorScheme.onPrimary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Standard emoji category tabs (index 1 to emojiCategories.size)
                    // Filter out the "Recent" category since we manually create the recent tab
                    val filteredCategories = emojiCategories.filter { it.name != "Recent" }
                    items(
                        items = filteredCategories,
                        key = { it.name }
                    ) { category ->
                        // Calculate index based on position in original list (before filtering)
                        val originalIndex = emojiCategories.indexOf(category)
                        val categoryIndex = originalIndex + 1 // Offset by 1 for recent tab
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedCategory = categoryIndex },
                            color = if (selectedCategory == categoryIndex) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = category.icon,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selectedCategory == categoryIndex) 
                                    MaterialTheme.colorScheme.onPrimary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Custom emoji pack tabs - filter out any packs with displayName matching the recent tab icon
                    val filteredCustomPacks = customEmojiPacks.filter { it.displayName != "🕒" && it.displayName.isNotBlank() }
                    items(
                        items = filteredCustomPacks,
                        key = { "${it.roomId}_${it.packName}" }
                    ) { pack ->
                        // Calculate index based on position in original list (before filtering)
                        val originalIndex = customEmojiPacks.indexOf(pack)
                        val packIndex = 1 + emojiCategories.size + originalIndex // Offset by recent + standard categories
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedCategory = packIndex },
                            color = if (selectedCategory == packIndex) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = pack.displayName,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selectedCategory == packIndex) 
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
                    // Determine which emojis to show based on selected category
                    val baseEmojis: List<Any> = when {
                        selectedCategory == 0 -> recentEmojis // Recent tab
                        selectedCategory <= emojiCategories.size -> {
                            // Standard emoji category (offset by 1 for recent tab)
                            emojiCategories[selectedCategory - 1].emojis
                        }
                        else -> {
                            // Custom emoji pack (offset by 1 + emojiCategories.size)
                            val packIndex = selectedCategory - 1 - emojiCategories.size
                            if (packIndex >= 0 && packIndex < customEmojiPacks.size) {
                                customEmojiPacks[packIndex].emojis
                            } else {
                                emptyList()
                            }
                        }
                    }
                    
                    // Filter emojis based on search text
                    // When searching, search across all categories for better results
                    val filteredEmojis = if (searchText.isBlank()) {
                        baseEmojis
                    } else {
                        when {
                            selectedCategory == 0 -> {
                                // Search recent emojis
                                baseEmojis.filter { (it as? String)?.contains(searchText, ignoreCase = true) == true }
                            }
                            selectedCategory <= emojiCategories.size -> {
                                // Search across all standard emojis
                                EmojiData.getAllEmojis().filter { it.contains(searchText, ignoreCase = true) }
                            }
                            else -> {
                                // Search custom emojis in current pack
                                baseEmojis.filter { 
                                    (it as? net.vrkknn.andromuks.AppViewModel.CustomEmoji)?.name?.contains(searchText, ignoreCase = true) == true 
                                }
                            }
                        }
                    }
                    
                    items(filteredEmojis.size) { index ->
                        val emoji = filteredEmojis[index]
                        Surface(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { 
                                    when (emoji) {
                                        is String -> {
                                            onEmojiSelected(emoji)
                                        }
                                        is net.vrkknn.andromuks.AppViewModel.CustomEmoji -> {
                                            // Format custom emoji as ![:name:](mxc://url "Emoji: :name:")
                                            val formatted = "![:${emoji.name}:](${emoji.mxcUrl} \"Emoji: :${emoji.name}:\")"
                                            onEmojiSelected(formatted)
                                        }
                                    }
                                    onDismiss()
                                },
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                when (emoji) {
                                    is String -> {
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
                                    is net.vrkknn.andromuks.AppViewModel.CustomEmoji -> {
                                        // Handle custom emojis
                                        ImageEmoji(emoji.mxcUrl, homeserverUrl, authToken)
                                    }
                                }
                            }
                        }
                    }
                    
                    // Custom reaction button if search text is not empty and not found in emojis
                    val searchFound = baseEmojis.any { emoji ->
                        when (emoji) {
                            is String -> emoji.contains(searchText, ignoreCase = true)
                            is net.vrkknn.andromuks.AppViewModel.CustomEmoji -> emoji.name.contains(searchText, ignoreCase = true)
                            else -> false
                        }
                    }
                    if (searchText.isNotBlank() && !searchFound) {
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
