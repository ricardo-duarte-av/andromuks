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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
    
    val spaces = appViewModel.spaceList
    if (spaces.isEmpty()) {
        Text("Loading rooms...", modifier = Modifier.padding(16.dp))
    } else {
        // Get all rooms from the first space (which should be "All Rooms")
        val allRooms = spaces.firstOrNull()?.rooms ?: emptyList()
        var searchQuery by remember { mutableStateOf("") }
        
        Column(modifier = modifier.fillMaxSize()) {
            // Top App Bar with profile and name
            TopAppBar(
                title = { 
                    Column {
                        Text("Profile", style = MaterialTheme.typography.titleMedium)
                        Text("@daedric:aguiarvieira.pt", style = MaterialTheme.typography.bodySmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
            
            // Search box with tonal elevation effect via Surface
            Surface(
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search rooms...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                )
            }
            
            // Room list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top
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
        verticalAlignment = Alignment.CenterVertically
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
        
        // Room info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = room.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (room.messagePreview != null) {
                Text(
                    text = room.messagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // Unread count badge
        if (room.unreadCount != null && room.unreadCount > 0) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = room.unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}