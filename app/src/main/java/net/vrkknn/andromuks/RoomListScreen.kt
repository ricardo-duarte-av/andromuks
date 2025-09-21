package net.vrkknn.andromuks

import android.util.Log
import android.view.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter

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
    AndromuksTheme {
        Surface {
            val spaces = appViewModel.spaceList
            if (spaces.isEmpty()) {
                Text("Loading spaces...", modifier = Modifier.padding(16.dp))
            } else {
                var selectedSpaceId by remember { mutableStateOf<String?>(null) }
                Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
                    Text("Spaces", style = MaterialTheme.typography.titleLarge)
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.Top
                    ) {
                        items(spaces.size) { idx ->
                            val space = spaces[idx]
                            SpaceListItem(
                                space = space,
                                isSelected = space.id == selectedSpaceId,
                                onClick = { selectedSpaceId = space.id }
                            )
                        }
                    }
                    if (selectedSpaceId != null) {
                        val selectedSpace = spaces.find { it.id == selectedSpaceId }
                        Log.d("RoomListScreen", "Selected space ${selectedSpace?.name} has ${selectedSpace?.rooms?.size} rooms")
                        if (selectedSpace != null) {
                            Text("Rooms in ${selectedSpace.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.Top
                            ) {
                                items(selectedSpace.rooms.size) { idx ->
                                    val room = selectedSpace.rooms[idx]
                                    RoomListItem(
                                        room = room,
                                        onRoomClick = { /* TODO: Navigate to room timeline */ }
                                    )
                                }
                            }
                        }
                    } else {
                        Text("Select a space to view its rooms", modifier = Modifier.padding(top = 16.dp))
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
    onRoomClick: (RoomItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onRoomClick(room) }
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Text(
            text = room.name,
            style = MaterialTheme.typography.titleMedium
        )
        // rest of room details here
    }
}