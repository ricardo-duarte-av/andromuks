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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.ui.components.AvatarImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

/**
 * Data class for bridged room entry
 */
data class BridgedRoomEntry(
    val roomId: String,
    val roomName: String?,
    val protocol: String,
    val protocolDisplayName: String,
    val protocolAvatarUrl: String?,
    val channelDisplayName: String?,
    val channelId: String?
)

/**
 * Screen to display all bridged rooms from the database
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgedRoomsScreen(
    appViewModel: AppViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val homeserverUrl = appViewModel.homeserverUrl
    val authToken = appViewModel.authToken
    var bridgedRooms by remember { mutableStateOf<List<BridgedRoomEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            isLoading = true
            val rooms = withContext(Dispatchers.IO) {
                appViewModel.getAllBridgedRooms(context)
            }
            
            // Convert to BridgedRoomEntry with room names
            bridgedRooms = rooms.map { (roomId, bridgeInfo) ->
                val roomName = appViewModel.allRooms.find { it.id == roomId }?.name
                BridgedRoomEntry(
                    roomId = roomId,
                    roomName = roomName,
                    protocol = bridgeInfo.protocol.id,
                    protocolDisplayName = bridgeInfo.protocol.displayname,
                    protocolAvatarUrl = bridgeInfo.protocol.avatarUrl,
                    channelDisplayName = bridgeInfo.channel.displayname,
                    channelId = bridgeInfo.channel.id
                )
            }.sortedBy { it.protocolDisplayName }
            
            isLoading = false
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridged Rooms") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
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
                CircularProgressIndicator()
            }
        } else if (bridgedRooms.isEmpty()) {
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
                        text = "No bridged rooms found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Bridge information is loaded when you access the Bridges tab",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Group by protocol
            val groupedByProtocol = bridgedRooms.groupBy { it.protocolDisplayName }
            val sortedProtocols = groupedByProtocol.keys.sorted()
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                item {
                    Text(
                        text = "${bridgedRooms.size} bridged rooms across ${sortedProtocols.size} networks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                // Grouped by protocol
                sortedProtocols.forEach { protocol ->
                    val roomsInProtocol = groupedByProtocol[protocol] ?: emptyList()
                    
                    // Protocol header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (roomsInProtocol.isNotEmpty() && roomsInProtocol.first().protocolAvatarUrl != null) {
                                AvatarImage(
                                    mxcUrl = roomsInProtocol.first().protocolAvatarUrl,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    fallbackText = protocol.take(1),
                                    displayName = protocol,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Text(
                                text = "$protocol (${roomsInProtocol.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    
                    // Rooms in this protocol
                    items(roomsInProtocol) { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate("room_info/${entry.roomId}")
                                },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = entry.roomName ?: entry.roomId,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                
                                if (entry.channelDisplayName != null && entry.channelDisplayName.isNotBlank()) {
                                    Text(
                                        text = entry.channelDisplayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                
                                if (entry.channelId != null && entry.channelId.isNotBlank()) {
                                    Text(
                                        text = "ID: ${entry.channelId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                
                                Text(
                                    text = entry.roomId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

