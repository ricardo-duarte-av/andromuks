package net.vrkknn.andromuks

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import net.vrkknn.andromuks.ui.theme.AndromuksTheme

//val mockRoomList = listOf(
//    RoomItem(id = "1", name = "There is a chat that never goes out"),
//    RoomItem(id = "2", name = "Chatting up the hill"),
//    RoomItem(id = "3", name = "Chat suey"),
//    RoomItem(id = "4", name = "Chatter's delight"),
//    RoomItem(id = "5", name = "Chat of glass")
//)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(
    navController: NavController,
    roomListViewModel: RoomListViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val rooms by roomListViewModel.roomList.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rooms") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        if (rooms.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                Text("No rooms yet, or still loading...")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(rooms, key = { room -> room.roomId }) { room ->
                    RoomListItem(
                        room = room,
                        onRoomClick = {
                            // TODO: implement this
                            Log.d("RoomListScreen", "Clicked on room: ${room.name} (ID: ${room.roomId})")
                        }
                    )
                    // fuck dividers
                }
            }
        }
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

@Preview(showBackground = true)
@Composable
fun RoomListScreenPreview() {
    AndromuksTheme {
        RoomListScreen(navController = rememberNavController())
    }
}

@Preview(showBackground = true)
@Composable
fun RoomListItemPreview() {
    AndromuksTheme {
        RoomListItem(
            room = RoomItem(roomId = "preview-1", name = "Preview Room Name"),
            onRoomClick = {}
        )
    }
}