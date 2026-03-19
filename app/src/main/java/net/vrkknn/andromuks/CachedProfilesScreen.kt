package net.vrkknn.andromuks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator

/**
 * Data class for cached profile entry
 */
data class CachedProfileEntry(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val roomId: String? = null // Room ID if this is a room-specific profile
)

/**
 * Screen to display cached user profiles (memory or disk)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CachedProfilesScreen(
    cacheType: String, // "memory" | "per_room" | "global" | "disk"(legacy)
    appViewModel: AppViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf<List<CachedProfileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(cacheType) {
        isLoading = true
        if (cacheType == "memory" || cacheType == "per_room" || cacheType == "global") {
            val roomProfiles = appViewModel.getAllMemoryCachedProfiles()
            val filtered = when (cacheType) {
                "per_room" -> roomProfiles.filter { it.roomId != null }
                "global" -> roomProfiles.filter { it.roomId == null }
                else -> roomProfiles
            }
            profiles = filtered.map { roomProfile ->
                CachedProfileEntry(
                    userId = roomProfile.userId,
                    displayName = roomProfile.profile.displayName,
                    avatarUrl = roomProfile.profile.avatarUrl,
                    roomId = roomProfile.roomId
                )
            }
        } else {
            // Profiles are in-memory only, loaded opportunistically when rendering events
            // Disk cache no longer exists, so show empty list
            profiles = emptyList()
        }
        isLoading = false
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = when (cacheType) {
                            "per_room" -> "Profile Gallery (Per-Room)"
                            "global" -> "Profile Gallery (Global)"
                            "memory" -> "Profile Gallery (Memory)"
                            else -> "Profile Gallery"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
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
        } else if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No cached profiles found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Text(
                            text = "${profiles.size} profile${if (profiles.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (cacheType == "memory") {
                            val uniqueUsers = profiles.map { it.userId }.distinct().size
                            val perRoomCount = profiles.count { it.roomId != null }
                            val globalCount = profiles.size - perRoomCount
                            if (uniqueUsers < profiles.size) {
                                Text(
                                    text = "$uniqueUsers unique user${if (uniqueUsers != 1) "s" else ""} (some appear in multiple rooms)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Per-room: $perRoomCount  •  Global: $globalCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (cacheType == "per_room") {
                            Text(
                                text = "Showing room-specific profile variants only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (cacheType == "global") {
                            Text(
                                text = "Showing global profile entries only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                items(profiles) { profile ->
                    CachedProfileItem(
                        profile = profile,
                        appViewModel = appViewModel,
                        onClick = {
                            navController.navigate("user_info/${profile.userId}")
                        }
                    )
                }
            }
        }
    }
}

/**
 * Individual profile item in the list
 */
@Composable
fun CachedProfileItem(
    profile: CachedProfileEntry,
    appViewModel: AppViewModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            AvatarImage(
                mxcUrl = profile.avatarUrl,
                homeserverUrl = appViewModel.homeserverUrl,
                authToken = appViewModel.authToken,
                fallbackText = profile.displayName ?: profile.userId,
                size = 48.dp,
                userId = profile.userId,
                displayName = profile.displayName
            )
            
            // Display Name, User ID, and Room ID (if room-specific)
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = profile.displayName ?: profile.userId,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = profile.userId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Show room ID if this is a room-specific profile
                if (profile.roomId != null) {
                    Text(
                        text = "Room: ${profile.roomId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

