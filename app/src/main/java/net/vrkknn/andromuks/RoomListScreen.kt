package net.vrkknn.andromuks

import android.util.Log
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import android.content.Context

val mockRoomList = listOf(
    RoomItem(id = "1", name = "There is a chat that never goes out", messagePreview = "last message", unreadCount = 1, avatarUrl = null),
    RoomItem(id = "2", name = "Chatting up the hill", messagePreview = "last message", unreadCount = 0, avatarUrl = null),
    RoomItem(id = "3", name = "Chat suey", messagePreview = "last message", unreadCount = 0, avatarUrl = null),
    RoomItem(id = "4", name = "Chatter's delight", messagePreview = "last message", unreadCount = 12, avatarUrl = null),
    RoomItem(id = "5", name = "Chat of glass", messagePreview = "last message", unreadCount = 0, avatarUrl = null)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val sharedPreferences = remember(context) { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val authToken = remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    
    val spaces = appViewModel.spaceList
    if (spaces.isEmpty()) {
        Text("Loading rooms...", modifier = Modifier.padding(16.dp))
    } else {
        // Get all rooms from the first space (which should be "All Rooms")
        val allRooms = spaces.firstOrNull()?.rooms ?: emptyList()
        var searchQuery by remember { mutableStateOf("") }
        val me = appViewModel.currentUserProfile
        
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
        ) {
            // Compact header with our avatar and name (no colored area)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                net.vrkknn.andromuks.ui.components.AvatarImage(
                    mxcUrl = me?.avatarUrl,
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = authToken,
                    fallbackText = me?.displayName ?: appViewModel.currentUserId,
                    size = 40.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = me?.displayName ?: appViewModel.currentUserId.ifBlank { "Profile" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!me?.displayName.isNullOrBlank() && appViewModel.currentUserId.isNotBlank()) {
                        Text(
                            text = appViewModel.currentUserId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            
            // Search box with rounded look and trailing search icon
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp) // pick your pill height
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search rooms…") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        focusedIndicatorColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            
            // Room list in elevated frame
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(
                    topStart = 24.dp,
                    topEnd = 24.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                ),
                tonalElevation = 2.dp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Top,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 8.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp
                    )
                ) {
                    val filteredRooms = if (searchQuery.isBlank()) {
                        allRooms
                    } else {
                        allRooms.filter { room ->
                            room.name.contains(searchQuery, ignoreCase = true)
                        }
                    }
                    
                    items(filteredRooms.size) { idx ->
                        val room = filteredRooms[idx]
                        RoomListItem(
                            room = room,
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = authToken,
                            onRoomClick = { 
                                navController.navigate("room_timeline/${room.id}")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SpaceListItem(space: SpaceItem, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 8.dp)
    ) {
        Text(
            text = space.name,
            style = if (isSelected) MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary)
            else MaterialTheme.typography.titleMedium
        )
        // TODO: Add avatar image if available
    }
}

@Composable
fun RoomListItem(
    room: RoomItem,
    homeserverUrl: String,
    authToken: String,
    onRoomClick: (RoomItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onRoomClick(room) }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Room avatar
        net.vrkknn.andromuks.ui.components.AvatarImage(
            mxcUrl = room.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = room.name,
            size = 48.dp
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Room info with time and unread badge
        Box(
            modifier = Modifier.weight(1f)
        ) {
            Column {
                // Room name and unread badge row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = room.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Right side: unread pill (top) and time (below)
                    Column(horizontalAlignment = Alignment.End) {
                        if (room.unreadCount != null && room.unreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = room.unreadCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }

                        val timeAgoInline = formatTimeAgo(room.sortingTimestamp)
                        if (timeAgoInline.isNotEmpty()) {
                            Text(
                                text = timeAgoInline,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                
                // Message preview
                if (room.messagePreview != null) {
                    Text(
                        text = room.messagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private fun formatTimeAgo(timestamp: Long?): String {
    if (timestamp == null) return ""
    
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "${diff / 1000}s" // Less than 1 minute: show seconds
        diff < 3_600_000 -> "${diff / 60_000}m" // Less than 1 hour: show minutes
        diff < 86_400_000 -> "${diff / 3_600_000}h" // Less than 1 day: show hours
        diff < 604_800_000 -> "${diff / 86_400_000}d" // Less than 1 week: show days
        else -> "${diff / 604_800_000}w" // More than 1 week: show weeks
    }
}