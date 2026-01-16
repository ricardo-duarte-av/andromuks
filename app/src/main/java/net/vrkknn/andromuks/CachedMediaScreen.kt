package net.vrkknn.andromuks

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.request.ImageRequest
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import java.io.File

/**
 * Data class for cached media entry (matches AppViewModel.CachedMediaEntry)
 */
typealias CachedMediaEntry = net.vrkknn.andromuks.AppViewModel.CachedMediaEntry

/**
 * Screen to display cached media gallery (memory or disk)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CachedMediaScreen(
    cacheType: String, // "memory" or "disk"
    appViewModel: AppViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var mediaEntries by remember { mutableStateOf<List<CachedMediaEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    fun loadMedia() {
        coroutineScope.launch {
            if (mediaEntries.isNotEmpty()) {
                isRefreshing = true
            } else {
                isLoading = true
            }
            
            val entries = if (cacheType == "memory") {
                appViewModel.getAllMemoryCachedMedia(context)
            } else {
                appViewModel.getAllDiskCachedMedia(context)
            }
            
            mediaEntries = entries
            isLoading = false
            isRefreshing = false
        }
    }
    
    LaunchedEffect(cacheType) {
        loadMedia()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            text = if (cacheType == "memory") "Memory Cached Media" else "Disk Cached Media"
                        )
                        if (cacheType == "memory") {
                            Text(
                                text = "Note: Coil's RAM cache cannot be enumerated. Showing disk cache (loaded into RAM when accessed).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { loadMedia() },
                        enabled = !isRefreshing && !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh media cache"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                ExpressiveLoadingIndicator()
            }
        } else if (mediaEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No cached media found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Media will appear here once cached",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Statistics header
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${mediaEntries.size} media item${if (mediaEntries.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        val totalSize = mediaEntries.sumOf { it.fileSize }
                        Text(
                            text = "Total size: ${appViewModel.formatBytes(totalSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (cacheType == "memory") {
                            Text(
                                text = "Coil's RAM cache (bitmaps) cannot be enumerated. This shows Coil's disk cache, which represents images that could be in RAM when accessed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                
                // Media gallery grid (lazy loading to reduce memory pressure)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = mediaEntries,
                        key = { it.filePath } // Use file path as key for better recomposition
                    ) { entry ->
                        // Only load image when visible (lazy loading)
                        CachedMediaItem(
                            entry = entry,
                            appViewModel = appViewModel
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual media item in the gallery
 */
@Composable
fun CachedMediaItem(
    entry: CachedMediaEntry,
    appViewModel: AppViewModel
) {
    val context = LocalContext.current
    val imageLoader = remember { net.vrkknn.andromuks.utils.ImageLoaderSingleton.get(context) }
    var showFullUrl by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showFullUrl = !showFullUrl },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Image thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                if (entry.file.exists() && entry.file.canRead()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(entry.file)
                            .crossfade(true)
                            .size(coil.size.Size(200, 200)) // Limit size to reduce memory usage
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                            .diskCachePolicy(coil.request.CachePolicy.DISABLED) // Already on disk
                            .build(),
                        contentDescription = entry.mxcUrl ?: "Cached media",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        imageLoader = imageLoader
                    )
                } else {
                    // File doesn't exist or can't be read
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = "Broken image",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // MXC URL or file info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (entry.mxcUrl != null) {
                    Text(
                        text = entry.mxcUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (showFullUrl) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "Unknown MXC URL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = entry.file.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Text(
                    text = "${appViewModel.formatBytes(entry.fileSize)} • ${entry.cacheType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // Show full URL dialog if clicked
    if (showFullUrl && entry.mxcUrl != null) {
        AlertDialog(
            onDismissRequest = { showFullUrl = false },
            title = { Text("MXC URL") },
            text = {
                Column {
                    Text(
                        text = entry.mxcUrl,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "File: ${entry.file.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Size: ${appViewModel.formatBytes(entry.fileSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullUrl = false }) {
                    Text("Close")
                }
            }
        )
    }
}


