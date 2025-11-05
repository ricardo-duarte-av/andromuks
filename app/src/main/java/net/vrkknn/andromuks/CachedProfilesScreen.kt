package net.vrkknn.andromuks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/**
 * Data class for cached profile entry
 */
data class CachedProfileEntry(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?
)

/**
 * Screen to display cached user profiles (memory or disk)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CachedProfilesScreen(
    cacheType: String, // "memory" or "disk"
    appViewModel: AppViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf<List<CachedProfileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(cacheType) {
        isLoading = true
        val profileList = if (cacheType == "memory") {
            appViewModel.getAllMemoryCachedProfiles()
        } else {
            appViewModel.getAllDiskCachedProfiles(context)
        }
        
        profiles = profileList.map { (userId, profile) ->
            CachedProfileEntry(
                userId = userId,
                displayName = profile.displayName,
                avatarUrl = profile.avatarUrl
            )
        }
        isLoading = false
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (cacheType == "memory") "Memory Cached Profiles" else "Disk Cached Profiles"
                    )
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
                CircularProgressIndicator()
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
                    Text(
                        text = "${profiles.size} profile${if (profiles.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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
            
            // Display Name and User ID
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
            }
        }
    }
}

