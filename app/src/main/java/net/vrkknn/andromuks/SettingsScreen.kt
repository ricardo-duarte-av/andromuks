package net.vrkknn.andromuks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    navController: NavController
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Top App Bar
        TopAppBar(
            title = { Text("Settings") }
        )

        // Settings Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Display Settings Section
            Text(
                text = "Display Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // Show Unprocessed Events Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Show unprocessed events",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Display low-level events like m.room.create, m.room.join_rules, etc.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = appViewModel.showUnprocessedEvents,
                        onCheckedChange = { appViewModel.toggleShowUnprocessedEvents() }
                    )
                }
            }
            
            // Load thumbnails setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Load thumbnails if available",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Use image thumbnails in timelines when provided (saves bandwidth).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = appViewModel.loadThumbnailsIfAvailable,
                        onCheckedChange = { appViewModel.toggleLoadThumbnailsIfAvailable() }
                    )
                }
            }
            
            // Compression Setting
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Compression",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Request compression from server to client",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = appViewModel.enableCompression,
                        onCheckedChange = { appViewModel.toggleCompression() }
                    )
                }
            }
            
            // FCM Information Section
            Text(
                text = "Push Notifications",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            FCMInfoSection(appViewModel = appViewModel)

            // Cache Statistics Section
            Text(
                text = "Cache Statistics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            CacheStatisticsSection(appViewModel = appViewModel, navController = navController)

            // WebSocket Debug Section
            Text(
                text = "WebSocket Debug",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Activity Log",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "View WebSocket activity history (connections, disconnections, etc.)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Button(
                        onClick = { navController.navigate("reconnection_log") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View Activity Log")
                    }
                }
            }

            // About Section
            Text(
                text = "About",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Andromuks",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "A Matrix client for Android",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun FCMInfoSection(appViewModel: AppViewModel) {
    val context = LocalContext.current
    var showSnackbar by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("") }
    
    // Get FCM-related data
    val fcmNotificationManager = remember { FCMNotificationManager(context) }
    val webClientPushIntegration = remember { WebClientPushIntegration(context) }
    
    val fcmToken = remember { fcmNotificationManager.getTokenForGomuksBackend() }
    val deviceId = remember { webClientPushIntegration.getDeviceID() }
    val encryptionKey = remember { 
        try {
            val prefs = context.getSharedPreferences("web_client_prefs", Context.MODE_PRIVATE)
            prefs.getString("push_encryption_key", null)
        } catch (e: Exception) {
            null
        }
    }
    val vapidKey = remember { appViewModel.getVapidKey() }
    val runId = remember { appViewModel.getCurrentRunId() }
    val currentRequestId = remember { appViewModel.getCurrentRequestId() }
    val lastReceivedRequestId = remember { appViewModel.getLastReceivedRequestId() }
    val homeserverUrl = remember { appViewModel.homeserverUrl }
    
    // Construct the current WebSocket URL (no longer includes last_received_id)
    val currentWebSocketUrl = remember(homeserverUrl, runId) {
        if (homeserverUrl.isBlank()) {
            "Not available"
        } else {
            // Use the same logic as NetworkUtils.trimWebsocketHost()
            val baseUrl = if (homeserverUrl.lowercase().trim().startsWith("https://")) {
                homeserverUrl.substringAfter("https://")
            } else if (homeserverUrl.lowercase().trim().startsWith("http://")) {
                homeserverUrl.substringAfter("http://")
            } else {
                homeserverUrl
            }
            val wsHost = baseUrl.split("/").firstOrNull() ?: baseUrl
            val baseWebSocketUrl = "wss://$wsHost/_gomuks/websocket"
            
            if (runId.isNotEmpty()) {
                "$baseWebSocketUrl?run_id=$runId"
            } else {
                baseWebSocketUrl
            }
        }
    }
    val isRegistered = remember { fcmNotificationManager.isRegisteredWithBackend() }
    val lastRegistration = remember {
        val prefs = context.getSharedPreferences("web_client_prefs", Context.MODE_PRIVATE)
        prefs.getLong("last_push_reg", 0L)
    }
    
    // Helper function to copy to clipboard
    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        snackbarMessage = "$label copied to clipboard"
        showSnackbar = true
    }
    
    // Helper function to format timestamp
    fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "Never"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Registration Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Registration Status",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Surface(
                    color = if (isRegistered) MaterialTheme.colorScheme.primaryContainer 
                           else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (isRegistered) "Registered" else "Not Registered",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isRegistered) MaterialTheme.colorScheme.onPrimaryContainer 
                               else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            
            HorizontalDivider()
            
            // FCM Token
            FCMInfoItem(
                label = "FCM Token",
                value = fcmToken ?: "Not available",
                onCopy = if (fcmToken != null) {
                    { copyToClipboard("FCM Token", fcmToken) }
                } else null
            )
            
            HorizontalDivider()
            
            // Device ID
            FCMInfoItem(
                label = "Device ID",
                value = deviceId ?: "Not available",
                onCopy = if (deviceId != null) {
                    { copyToClipboard("Device ID", deviceId) }
                } else null
            )
            
            HorizontalDivider()
            
            // Encryption Key
            FCMInfoItem(
                label = "Encryption Key",
                value = encryptionKey ?: "Not available",
                onCopy = if (encryptionKey != null) {
                    { copyToClipboard("Encryption Key", encryptionKey) }
                } else null
            )
            
            HorizontalDivider()
            
            // VAPID Key
            FCMInfoItem(
                label = "VAPID Key",
                value = vapidKey ?: "Not available",
                onCopy = if (vapidKey != null) {
                    { copyToClipboard("VAPID Key", vapidKey) }
                } else null
            )
            
            HorizontalDivider()
            
            // Run ID
            FCMInfoItem(
                label = "Run ID",
                value = runId.ifEmpty { "Not available" },
                onCopy = if (runId.isNotEmpty()) {
                    { copyToClipboard("Run ID", runId) }
                } else null
            )
            
            HorizontalDivider()
            
            // Current Request ID
            FCMInfoItem(
                label = "Current Request ID",
                value = currentRequestId.toString(),
                onCopy = { copyToClipboard("Current Request ID", currentRequestId.toString()) }
            )
            
            HorizontalDivider()
            
            // Last Received Request ID
            FCMInfoItem(
                label = "Last Received Request ID",
                value = if (lastReceivedRequestId != 0) lastReceivedRequestId.toString() else "Not set",
                onCopy = if (lastReceivedRequestId != 0) {
                    { copyToClipboard("Last Received Request ID", lastReceivedRequestId.toString()) }
                } else null
            )
            
            HorizontalDivider()
            
            // WebSocket URL
            FCMInfoItem(
                label = "WebSocket URL",
                value = currentWebSocketUrl,
                onCopy = if (currentWebSocketUrl != "Not available") {
                    { copyToClipboard("WebSocket URL", currentWebSocketUrl) }
                } else null
            )
            
            HorizontalDivider()
            
            // Last Registration
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last Registration",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatTimestamp(lastRegistration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
    
    // Snackbar for copy confirmation
    if (showSnackbar) {
        LaunchedEffect(snackbarMessage) {
            kotlinx.coroutines.delay(2000)
            showSnackbar = false
        }
        
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = { showSnackbar = false }) {
                    Text("Dismiss")
                }
            }
        ) {
            Text(snackbarMessage)
        }
    }
}

@Composable
fun FCMInfoItem(
    label: String,
    value: String,
    onCopy: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy $label",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun CacheStatisticsSection(appViewModel: AppViewModel, navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var cacheStats by remember { mutableStateOf<Map<String, String>?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    // Helper function to refresh cache stats
    fun refreshStats() {
        coroutineScope.launch {
            isRefreshing = true
            // Run in background to avoid blocking UI
            cacheStats = withContext(kotlinx.coroutines.Dispatchers.Default) {
                appViewModel.getCacheStatistics(context)
            }
            isRefreshing = false
        }
    }
    
    // Refresh cache statistics when screen is composed
    LaunchedEffect(Unit) {
        refreshStats()
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Memory & Cache Usage",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                IconButton(
                    onClick = { refreshStats() },
                    enabled = !isRefreshing
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh cache statistics",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            if (isRefreshing) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (cacheStats != null) {
                HorizontalDivider()
                
                // App RAM Usage
                CacheStatItem(
                    label = "App RAM Usage",
                    value = cacheStats!!["app_ram_usage"] ?: "N/A",
                    description = "Max: ${cacheStats!!["app_ram_max"] ?: "N/A"}"
                )
                
                HorizontalDivider()
                
                // Room Timeline Memory Cache
                CacheStatItem(
                    label = "Room Timeline Cache",
                    value = cacheStats!!["timeline_memory_cache"] ?: "N/A",
                    description = cacheStats!!["timeline_event_count"] ?: ""
                )
                
                HorizontalDivider()
                
                // User Profiles Memory Cache
                CacheStatItem(
                    label = "User Profiles (Memory)",
                    value = cacheStats!!["user_profiles_memory_cache"] ?: "N/A",
                    description = cacheStats!!["user_profiles_count"] ?: "",
                    onClick = { navController.navigate("cached_profiles/memory") }
                )
                
                HorizontalDivider()
                
                // User Profiles Disk Cache
                CacheStatItem(
                    label = "User Profiles (Disk)",
                    value = cacheStats!!["user_profiles_disk_cache"] ?: "N/A",
                    description = "Stored in SQLite database",
                    onClick = { navController.navigate("cached_profiles/disk") }
                )
                
                HorizontalDivider()
                
                // Media Memory Cache
                CacheStatItem(
                    label = "Media Cache (Memory)",
                    value = cacheStats!!["media_memory_cache"] ?: "N/A",
                    description = cacheStats!!["media_memory_cache_max"]?.let { "Max size: $it (actual usage not available)" } ?: "Coil image cache (max size)",
                    onClick = { navController.navigate("cached_media/memory") }
                )
                
                HorizontalDivider()
                
                // Media Disk Cache
                CacheStatItem(
                    label = "Media Cache (Disk)",
                    value = cacheStats!!["media_disk_cache"] ?: "N/A",
                    description = "Coil disk cache",
                    onClick = { navController.navigate("cached_media/disk") }
                )
                
            }
        }
    }
}

@Composable
fun CacheStatItem(
    label: String,
    value: String,
    description: String,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
