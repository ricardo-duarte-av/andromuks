package net.vrkknn.andromuks

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme

/**
 * A simplified room list that only shows the room display name, avatar (when available) and room ID.
 *
 * This screen reuses the existing [RoomItem] data exposed by [AppViewModel.allRooms], avoiding the
 * complex filtering / animation logic in [RoomListScreen].
 */
@Composable
fun SimplerRoomListScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val sharedPreferences =
        remember(context) {
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        }
    val authToken =
        remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    val homeserverUrl = appViewModel.homeserverUrl

    // Observe rooms from the view model. allRooms already updates reactively via mutableStateOf.
    val rooms = appViewModel.allRooms
    val pendingShare = appViewModel.pendingShare

    AndromuksTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (pendingShare == null) {
                EmptyRoomListPlaceholder(
                    title = "Nothing to share",
                    message = "We couldn't access the media you tried to share.",
                    actionLabel = "Go back"
                ) {
                    navController.popBackStack()
                    appViewModel.clearPendingShare()
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    var searchQuery by rememberSaveable { mutableStateOf("") }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        tonalElevation = 3.dp,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            placeholder = { Text("Search rooms") },
                            leadingIcon = {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search rooms"
                                )
                            },
                            singleLine = true
                        )
                    }

                    Text(
                        text = "Select a room to share this media",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Media will be uploaded after you confirm in the preview.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (rooms.isEmpty()) {
                        EmptyRoomListPlaceholder(
                            title = "No rooms available",
                            message = "Once rooms are synced, they'll appear here.",
                            actionLabel = "Go back"
                        ) {
                            navController.popBackStack()
                            appViewModel.clearPendingShare()
                        }
                    } else {
                        val filteredRooms =
                            if (searchQuery.isBlank()) {
                                rooms
                            } else {
                                rooms.filter { room ->
                                    room.name.contains(searchQuery, ignoreCase = true) ||
                                        room.id.contains(searchQuery, ignoreCase = true)
                                }
                            }

                        if (filteredRooms.isEmpty()) {
                            EmptyRoomListPlaceholder(
                                title = "No rooms match your search",
                                message = "Try a different room name or identifier."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(
                                    items = filteredRooms,
                                    key = { room -> room.id }
                                ) { room ->
                                    RoomListRow(
                                        room = room,
                                        homeserverUrl = homeserverUrl,
                                        authToken = imageToken
                                    ) {
                                        appViewModel.selectPendingShareRoom(room.id)
                                        appViewModel.navigateToRoomWithCache(room.id)
                                        navController.navigate("room_timeline/${room.id}") {
                                            popUpTo("simple_room_list") { inclusive = true }
                                        }
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomListRow(
    room: RoomItem,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            mxcUrl = room.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = room.name,
            size = 48.dp,
            userId = room.id,
            displayName = room.name
        )
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = room.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = room.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyRoomListPlaceholder(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

