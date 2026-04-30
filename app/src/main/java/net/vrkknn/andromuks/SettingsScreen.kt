package net.vrkknn.andromuks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ArrowBack
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
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    navController: NavController
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var elementCallBaseUrl by remember { mutableStateOf(appViewModel.elementCallBaseUrl) }
            LaunchedEffect(appViewModel.elementCallBaseUrl) {
                if (elementCallBaseUrl != appViewModel.elementCallBaseUrl) {
                    elementCallBaseUrl = appViewModel.elementCallBaseUrl
                }
            }

            // ── Client Preferences Section (top — gomuks cross-device prefs) ──
            Text(
                text = "Client Preferences",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
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
                        text = "Gomuks preferences",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Fine-grained preferences synced across devices (via account data) or kept local to this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { navController.navigate("client_preferences") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Client Preferences")
                    }
                }
            }

            // ── Room List Section ─────────────────────────────────────────────
            Text(
                text = "Room List",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Room list bottom bar layout (4 vs 6 tabs)
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
                            text = "Show all room list tabs",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Enable the full 6-button bottom bar (adds Favourites and Bridges tabs). When disabled, a compact 4-button bar is used.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = appViewModel.showAllRoomListTabs,
                        onCheckedChange = { appViewModel.toggleShowAllRoomListTabs() }
                    )
                }
            }

            // ── Room Timeline Section ────────────────────────────────────────
            Text(
                text = "Room Timeline",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Move read receipts to the edge
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
                            text = "Move read receipts to the edge",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Show read receipts on the opposite side of the screen from the message bubble (left for your messages, right for others).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = appViewModel.moveReadReceiptsToEdge,
                        onCheckedChange = { appViewModel.toggleMoveReadReceiptsToEdge() }
                    )
                }
            }

            // Trim long display names
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
                            text = "Trim long display names",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "If a user's display name is longer than 40 characters, it will be trimmed and suffixed with \"...\" when rendering in the timeline.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = appViewModel.trimLongDisplayNames,
                        onCheckedChange = { appViewModel.toggleTrimLongDisplayNames() }
                    )
                }
            }

            // ── Background Sync Section ──────────────────────────────────────
            Text(
                text = "Background Sync",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp)
            )

            BackgroundSyncSettings(appViewModel = appViewModel)

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
                            text = "Request compression from server to client. ⚠️ WARNING: Enabling compression significantly increases battery usage, even when the app is idle. Each message (4-8 per second) requires CPU-intensive decompression, preventing the device from entering deep sleep.",
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

            // Calls Section
            Text(
                text = "Calls",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
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
                        text = "Element Call base URL",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Set the base URL of your Element Call deployment. If it does not point to /element-call-embedded, the app will use the gomuks backend's embedded endpoint when available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextField(
                        value = elementCallBaseUrl,
                        onValueChange = {
                            elementCallBaseUrl = it
                            appViewModel.updateElementCallBaseUrl(it)
                        },
                        singleLine = true,
                        placeholder = { Text("https://call.example.com/") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            // FCM Information Section
            Text(
                text = "Push Notifications",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
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
                        text = "Push Notifications Details",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "View FCM registration state, identifiers, and debug values on a dedicated screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { navController.navigate("push_notifications_debug") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Push Notifications")
                    }
                }
            }

            // Cache Statistics Section
            Text(
                text = "Cache Statistics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
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
                        text = "Memory & Cache Usage",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Open a dedicated view with detailed memory and cache usage information.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { navController.navigate("cache_memory_stats") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Memory & Cache")
                    }
                }
            }

            // Account data (Matrix client account_data cache)
            Text(
                text = "Account data",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
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
                        text = "Account data viewer",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Inspect cached account_data keys from sync (e.g. m.direct, m.push_rules, preferences). Tap a key to view its JSON.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { navController.navigate("account_data_visualizer") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open account data")
                    }
                }
            }

            // WebSocket Debug Section
            Text(
                text = "WebSocket Debug",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
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
                color = MaterialTheme.colorScheme.onSurface,
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
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } // Content column
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushNotificationsDebugScreen(
    appViewModel: AppViewModel,
    navController: NavController
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Push Notifications") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            FCMInfoSection(appViewModel = appViewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheMemoryStatsScreen(
    appViewModel: AppViewModel,
    navController: NavController
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory & Cache Usage") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            CacheStatisticsSection(appViewModel = appViewModel, navController = navController)
        }
    }
}

@Composable
fun FCMInfoSection(appViewModel: AppViewModel) {
    val context = LocalContext.current
    var showSnackbar by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("") }
    var lastReceivedRequestId by remember { mutableStateOf(0) }
    var lastRegistration by remember { mutableStateOf(0L) }
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // Load persisted values when section is first shown
    LaunchedEffect(Unit) {
        lastReceivedRequestId = appViewModel.getLastReceivedRequestId()
        lastRegistration = context.getSharedPreferences("web_client_prefs", Context.MODE_PRIVATE).getLong("last_push_reg", 0L)
    }
    // After user taps "Re-register", refresh values once response may have been written
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            delay(2000)
            lastReceivedRequestId = appViewModel.getLastReceivedRequestId()
            lastRegistration = context.getSharedPreferences("web_client_prefs", Context.MODE_PRIVATE).getLong("last_push_reg", 0L)
        }
    }
    
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
    
    val isRegistered = remember { fcmNotificationManager.isRegisteredWithBackend() }
    
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
            // Registration Status (tappable to re-send register_push)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        appViewModel.registerFCMWithGomuksBackend(forceNow = true)
                        snackbarMessage = "Re-registering push…"
                        showSnackbar = true
                        refreshTrigger++
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Registration Status",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Tap to re-send register_push",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
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
                value = if (vapidKey.isNotBlank()) vapidKey else "Not available",
                onCopy = if (vapidKey.isNotBlank()) {
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
            delay(2000)
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
                    ExpressiveLoadingIndicator(modifier = Modifier.size(24.dp))
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

                // Profiles memory cache (room-specific)
                CacheStatItem(
                    label = "User Profiles (Per-Room Memory)",
                    value = cacheStats!!["user_profiles_room_memory_cache"] ?: "N/A",
                    description = cacheStats!!["user_profiles_room_count"] ?: "",
                    onClick = { navController.navigate("cached_profiles/per_room") }
                )

                HorizontalDivider()

                // Profiles memory cache (global)
                CacheStatItem(
                    label = "User Profiles (Global Memory)",
                    value = cacheStats!!["user_profiles_global_memory_cache"] ?: "N/A",
                    description = cacheStats!!["user_profiles_global_count"] ?: "",
                    onClick = { navController.navigate("cached_profiles/global") }
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

// ── Background Sync Settings Card ────────────────────────────────────────────

@Composable
fun BackgroundSyncSettings(appViewModel: AppViewModel) {
    // ── Purge interval (minutes) ─────────────────────────────────────────────
    var intervalText by remember { mutableStateOf(appViewModel.backgroundPurgeIntervalMinutes.toString()) }
    LaunchedEffect(appViewModel.backgroundPurgeIntervalMinutes) {
        intervalText = appViewModel.backgroundPurgeIntervalMinutes.toString()
    }

    // ── Message threshold ────────────────────────────────────────────────────
    var thresholdText by remember { mutableStateOf(appViewModel.backgroundPurgeMessageThreshold.toString()) }
    LaunchedEffect(appViewModel.backgroundPurgeMessageThreshold) {
        thresholdText = appViewModel.backgroundPurgeMessageThreshold.toString()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "While the app is backgrounded, incoming sync messages are buffered to save battery. " +
                        "The buffer is purged automatically when either threshold below is reached, " +
                        "or immediately when an FCM notification arrives.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── Interval slider + text field ─────────────────────────────────
            Text(
                text = "Purge interval",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Maximum time between automatic background purges (minutes).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Slider(
                    value = appViewModel.backgroundPurgeIntervalMinutes.toFloat(),
                    onValueChange = {
                        val newVal = it.toInt().coerceIn(1, 60)
                        appViewModel.updateBackgroundPurgeInterval(newVal)
                    },
                    valueRange = 1f..60f,
                    steps = 58, // 1-minute increments
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${appViewModel.backgroundPurgeIntervalMinutes} min",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(min = 52.dp)
                )
            }

            HorizontalDivider()

            // ── Message count threshold ──────────────────────────────────────
            Text(
                text = "Message count threshold",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Maximum buffered sync messages before an automatic purge is triggered, even if the interval hasn't elapsed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Slider(
                    value = appViewModel.backgroundPurgeMessageThreshold.toFloat(),
                    onValueChange = {
                        val newVal = it.toInt().coerceIn(10, 2000)
                        appViewModel.updateBackgroundPurgeThreshold(newVal)
                    },
                    valueRange = 10f..2000f,
                    steps = 0, // continuous
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${appViewModel.backgroundPurgeMessageThreshold}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(min = 52.dp)
                )
            }

            // ── Battery warning ──────────────────────────────────────────────
            val defaultInterval = (SyncBatchProcessor.DEFAULT_BATCH_INTERVAL_MS / 60_000L).toInt()
            val defaultThreshold = SyncBatchProcessor.DEFAULT_MAX_BATCH_SIZE
            if (appViewModel.backgroundPurgeIntervalMinutes < defaultInterval ||
                appViewModel.backgroundPurgeMessageThreshold < defaultThreshold
            ) {
                Text(
                    text = "⚠️ Lower values mean more frequent background processing, which may increase battery usage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ── Client Preferences Screen ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientPreferencesScreen(
    appViewModel: AppViewModel,
    navController: NavController
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Client Preferences") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Global (all devices)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            GomuksPreferenceCard(
                title = "Show image and video previews",
                description = "Stored in account data — applies across all your Matrix clients. " +
                    "When enabled, images and videos are rendered inline. When disabled, only a blurhash placeholder is shown until tapped.",
                value = appViewModel.accountGlobalShowMediaPreviews,
                onValueChange = { appViewModel.setGomuksGlobalPrefs(it) }
            )

            GomuksPreferenceCard(
                title = "Show link previews",
                description = "Stored in account data — applies across all your Matrix clients. " +
                    "When enabled, link preview cards from messages are displayed below the bubble.",
                value = appViewModel.accountGlobalRenderUrlPreviews,
                onValueChange = { appViewModel.setGomuksGlobalRenderUrlPreviews(it) }
            )

            GomuksPreferenceCard(
                title = "Send link previews",
                description = "Stored in account data — applies across all your Matrix clients. " +
                    "When enabled, a preview bar appears above the text input when a URL is typed and the preview is attached on send.",
                value = appViewModel.accountGlobalSendBundledUrlPreviews,
                onValueChange = { appViewModel.setGomuksGlobalSendBundledUrlPreviews(it) }
            )

            GomuksPreferenceCard(
                title = "Send read receipts",
                description = "Stored in account data — applies across all your Matrix clients. " +
                    "When disabled, mark_read commands are not sent, so others cannot see when you have read messages.",
                value = appViewModel.accountGlobalSendReadReceipts,
                onValueChange = { appViewModel.setGomuksGlobalSendReadReceipts(it) }
            )

            GomuksPreferenceCard(
                title = "Send typing notifications",
                description = "Stored in account data — applies across all your Matrix clients. " +
                    "When disabled, no typing indicator is sent while you type.",
                value = appViewModel.accountGlobalSendTypingNotifications,
                onValueChange = { appViewModel.setGomuksGlobalSendTypingNotifications(it) }
            )

            GomuksPreferenceCard(
                title = "Display read receipts",
                description = "Stored in account data — applies across all your Matrix clients. " +
                    "When disabled, read receipt avatars are hidden from the timeline.",
                value = appViewModel.accountGlobalDisplayReadReceipts,
                onValueChange = { appViewModel.setGomuksGlobalDisplayReadReceipts(it) }
            )

            Text(
                text = "Global (this device only)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )

            GomuksPreferenceCard(
                title = "Show image and video previews",
                description = "Stored locally on this device. Overrides the global (all-devices) setting. " +
                    "Use \"Default\" to inherit from the global preference.",
                value = appViewModel.deviceGlobalShowMediaPreviews,
                onValueChange = { appViewModel.setDeviceGlobalShowMediaPreviews(it) }
            )

            GomuksPreferenceCard(
                title = "Show link previews",
                description = "Stored locally on this device. Overrides the global (all-devices) setting.",
                value = appViewModel.deviceGlobalRenderUrlPreviews,
                onValueChange = { appViewModel.setDeviceGlobalRenderUrlPreviews(it) }
            )

            GomuksPreferenceCard(
                title = "Send link previews",
                description = "Stored locally on this device. Overrides the global (all-devices) setting.",
                value = appViewModel.deviceGlobalSendBundledUrlPreviews,
                onValueChange = { appViewModel.setDeviceGlobalSendBundledUrlPreviews(it) }
            )

            GomuksPreferenceCard(
                title = "Send read receipts",
                description = "Stored locally on this device. Overrides the global (all-devices) setting.",
                value = appViewModel.deviceGlobalSendReadReceipts,
                onValueChange = { appViewModel.setDeviceGlobalSendReadReceipts(it) }
            )

            GomuksPreferenceCard(
                title = "Send typing notifications",
                description = "Stored locally on this device. Overrides the global (all-devices) setting.",
                value = appViewModel.deviceGlobalSendTypingNotifications,
                onValueChange = { appViewModel.setDeviceGlobalSendTypingNotifications(it) }
            )

            GomuksPreferenceCard(
                title = "Display read receipts",
                description = "Stored locally on this device. Overrides the global (all-devices) setting.",
                value = appViewModel.deviceGlobalDisplayReadReceipts,
                onValueChange = { appViewModel.setDeviceGlobalDisplayReadReceipts(it) }
            )

            Text(
                text = "Resolution order",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Room (this device) > Room (all devices) > Global (this device) > Global (all devices). " +
                    "The first explicitly set value wins; \"Default\" means inherit from the next level.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GomuksPreferenceCard(
    title: String,
    description: String,
    value: Boolean?,
    onValueChange: (Boolean?) -> Unit
) {
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
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = value == true,
                    onClick = { onValueChange(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                ) { Text("On") }
                SegmentedButton(
                    selected = value == null,
                    onClick = { onValueChange(null) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                ) { Text("Default") }
                SegmentedButton(
                    selected = value == false,
                    onClick = { onValueChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                ) { Text("Off") }
            }
        }
    }
}

// ── Room Preferences Screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomPreferencesScreen(
    roomId: String,
    appViewModel: AppViewModel,
    navController: NavController
) {
    val roomPrefsVersion = appViewModel.gomuksRoomPrefsVersion
    var roomAccountShowMedia by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getAccountRoomShowMediaPreviews(roomId))
    }
    var roomDeviceShowMedia by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getDeviceRoomShowMediaPreviews(roomId))
    }
    var roomAccountRenderUrl by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getAccountRoomRenderUrlPreviews(roomId))
    }
    var roomDeviceRenderUrl by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getDeviceRoomRenderUrlPreviews(roomId))
    }
    var roomAccountSendBundled by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getAccountRoomSendBundledUrlPreviews(roomId))
    }
    var roomDeviceSendBundled by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getDeviceRoomSendBundledUrlPreviews(roomId))
    }
    var roomAccountSendReadReceipts by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getAccountRoomSendReadReceipts(roomId))
    }
    var roomDeviceSendReadReceipts by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getDeviceRoomSendReadReceipts(roomId))
    }
    var roomAccountSendTyping by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getAccountRoomSendTypingNotifications(roomId))
    }
    var roomDeviceSendTyping by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getDeviceRoomSendTypingNotifications(roomId))
    }
    var roomAccountDisplayReceipts by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getAccountRoomDisplayReadReceipts(roomId))
    }
    var roomDeviceDisplayReceipts by remember(roomId, roomPrefsVersion) {
        mutableStateOf(appViewModel.getDeviceRoomDisplayReadReceipts(roomId))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room Preferences") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Room (all devices)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            GomuksPreferenceCard(
                title = "Show image and video previews",
                description = "Stored in room account data — applies to this room on all your Matrix clients. " +
                    "Overrides the global preference for this room.",
                value = roomAccountShowMedia,
                onValueChange = {
                    roomAccountShowMedia = it
                    appViewModel.setGomuksRoomPrefs(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Show link previews",
                description = "Stored in room account data — applies to this room on all your Matrix clients. " +
                    "Overrides the global preference for this room.",
                value = roomAccountRenderUrl,
                onValueChange = {
                    roomAccountRenderUrl = it
                    appViewModel.setGomuksRoomRenderUrlPreviews(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Send link previews",
                description = "Stored in room account data — applies to this room on all your Matrix clients. " +
                    "Overrides the global preference for this room.",
                value = roomAccountSendBundled,
                onValueChange = {
                    roomAccountSendBundled = it
                    appViewModel.setGomuksRoomSendBundledUrlPreviews(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Send read receipts",
                description = "Stored in room account data — applies to this room on all your Matrix clients. " +
                    "When disabled, mark_read commands are not sent for this room.",
                value = roomAccountSendReadReceipts,
                onValueChange = {
                    roomAccountSendReadReceipts = it
                    appViewModel.setGomuksRoomSendReadReceipts(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Send typing notifications",
                description = "Stored in room account data — applies to this room on all your Matrix clients. " +
                    "When disabled, no typing indicator is sent while you type in this room.",
                value = roomAccountSendTyping,
                onValueChange = {
                    roomAccountSendTyping = it
                    appViewModel.setGomuksRoomSendTypingNotifications(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Display read receipts",
                description = "Stored in room account data — applies to this room on all your Matrix clients. " +
                    "When disabled, read receipt avatars are hidden in this room.",
                value = roomAccountDisplayReceipts,
                onValueChange = {
                    roomAccountDisplayReceipts = it
                    appViewModel.setGomuksRoomDisplayReadReceipts(roomId, it)
                }
            )

            Text(
                text = "Room (this device only)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )

            GomuksPreferenceCard(
                title = "Show image and video previews",
                description = "Stored locally on this device for this room. Overrides all other preferences for this room on this device. " +
                    "Use \"Default\" to inherit from the room (all-devices) setting.",
                value = roomDeviceShowMedia,
                onValueChange = {
                    roomDeviceShowMedia = it
                    appViewModel.setDeviceRoomShowMediaPreviews(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Show link previews",
                description = "Stored locally on this device for this room. Overrides all other preferences for this room on this device.",
                value = roomDeviceRenderUrl,
                onValueChange = {
                    roomDeviceRenderUrl = it
                    appViewModel.setDeviceRoomRenderUrlPreviews(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Send link previews",
                description = "Stored locally on this device for this room. Overrides all other preferences for this room on this device.",
                value = roomDeviceSendBundled,
                onValueChange = {
                    roomDeviceSendBundled = it
                    appViewModel.setDeviceRoomSendBundledUrlPreviews(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Send read receipts",
                description = "Stored locally on this device for this room. Overrides all other preferences for this room on this device.",
                value = roomDeviceSendReadReceipts,
                onValueChange = {
                    roomDeviceSendReadReceipts = it
                    appViewModel.setDeviceRoomSendReadReceipts(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Send typing notifications",
                description = "Stored locally on this device for this room. Overrides all other preferences for this room on this device.",
                value = roomDeviceSendTyping,
                onValueChange = {
                    roomDeviceSendTyping = it
                    appViewModel.setDeviceRoomSendTypingNotifications(roomId, it)
                }
            )

            GomuksPreferenceCard(
                title = "Display read receipts",
                description = "Stored locally on this device for this room. Overrides all other preferences for this room on this device.",
                value = roomDeviceDisplayReceipts,
                onValueChange = {
                    roomDeviceDisplayReceipts = it
                    appViewModel.setDeviceRoomDisplayReadReceipts(roomId, it)
                }
            )

            Text(
                text = "Resolution order",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Room (this device) > Room (all devices) > Global (this device) > Global (all devices). " +
                    "The first explicitly set value wins; \"Default\" means inherit from the next level.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
