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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import android.content.Context
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ArrowBack
import net.vrkknn.andromuks.ui.components.AvatarImage
import androidx.activity.compose.BackHandler

val mockRoomList = listOf(
    RoomItem(id = "1", name = "There is a chat that never goes out", messagePreview = "This is a message", messageSender = "Cursor", unreadCount = 1, avatarUrl = null),
    RoomItem(id = "2", name = "Chatting up the hill", messagePreview = "Hello everyone!", messageSender = "Alice", unreadCount = 0, avatarUrl = null),
    RoomItem(id = "3", name = "Chat suey", messagePreview = "How's it going?", messageSender = "Bob", unreadCount = 0, avatarUrl = null),
    RoomItem(id = "4", name = "Chatter's delight", messagePreview = "See you tomorrow", messageSender = "Charlie", unreadCount = 12, avatarUrl = null),
    RoomItem(id = "5", name = "Chat of glass", messagePreview = "Thanks for the help!", messageSender = "Diana", unreadCount = 0, avatarUrl = null)
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
    
    // Force recomposition by observing the update counter
    val updateCounter = appViewModel.updateCounter
    val currentSection = appViewModel.getCurrentRoomSection()
    android.util.Log.d("Andromuks", "RoomListScreen: currentSection = ${currentSection.type}, updateCounter = $updateCounter")
    
    android.util.Log.d("Andromuks", "RoomListScreen: currentSection.type = ${currentSection.type}, rooms.size = ${currentSection.rooms.size}, spaces.size = ${currentSection.spaces.size}")
    
    // Always show the interface, even if rooms/spaces are empty
    var searchQuery by remember { mutableStateOf("") }
    val me = appViewModel.currentUserProfile
    
    Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .imePadding()
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
                        color = MaterialTheme.colorScheme.onSurface,
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
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { 
                        Text(
                            "Search rooms…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ) 
                    },
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
                        focusedIndicatorColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
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
                // Main content area with tabs
                when (currentSection.type) {
                    RoomSectionType.HOME -> {
                        RoomListContent(
                            rooms = currentSection.rooms,
                            searchQuery = searchQuery,
                            appViewModel = appViewModel,
                            authToken = authToken,
                            navController = navController
                        )
                    }
                    RoomSectionType.SPACES -> {
                        if (appViewModel.currentSpaceId != null) {
                            // Show rooms within the selected space
                            RoomListContent(
                                rooms = currentSection.rooms,
                                searchQuery = searchQuery,
                                appViewModel = appViewModel,
                                authToken = authToken,
                                navController = navController
                            )
                        } else {
                            // Show list of spaces
                            SpacesListContent(
                                spaces = currentSection.spaces,
                                searchQuery = searchQuery,
                                appViewModel = appViewModel,
                                authToken = authToken,
                                navController = navController
                            )
                        }
                    }
                    RoomSectionType.DIRECT_CHATS -> {
                        RoomListContent(
                            rooms = currentSection.rooms,
                            searchQuery = searchQuery,
                            appViewModel = appViewModel,
                            authToken = authToken,
                            navController = navController
                        )
                    }
                    RoomSectionType.UNREAD -> {
                        RoomListContent(
                            rooms = currentSection.rooms,
                            searchQuery = searchQuery,
                            appViewModel = appViewModel,
                            authToken = authToken,
                            navController = navController
                        )
                    }
                }
            }
            
            // Tab bar at the bottom (outside the Surface)
            TabBar(
                currentSection = currentSection,
                onSectionSelected = { section ->
                    appViewModel.changeSelectedSection(section)
                },
                appViewModel = appViewModel
            )
        }
}

@Composable
fun SpaceListItem(
    space: SpaceItem, 
    isSelected: Boolean, 
    onClick: () -> Unit,
    homeserverUrl: String,
    authToken: String
) {
    android.util.Log.d("Andromuks", "SpaceListItem: Called for space: ${space.name}")
    android.util.Log.d("Andromuks", "SpaceListItem: Using homeserver URL: $homeserverUrl")
    
    // Calculate unread counts outside the Row
    val totalRooms = space.rooms.size
    val unreadRooms = space.rooms.count { it.unreadCount != null && it.unreadCount > 0 }
    val totalUnreadMessages = space.rooms.sumOf { it.unreadCount ?: 0 }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        AvatarImage(
            mxcUrl = space.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = space.name,
            size = 48.dp
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = space.name,
                style = when {
                    isSelected -> MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary)
                    unreadRooms > 0 -> MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    else -> MaterialTheme.typography.titleMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            if (totalRooms > 0) {
                Text(
                    text = "$totalRooms rooms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        
        // Unread badge - shows number of rooms with unread messages
        if (unreadRooms > 0) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (unreadRooms > 99) "99+" else "$unreadRooms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
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
                        style = if (room.unreadCount != null && room.unreadCount > 0) {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
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
                    val messageText = if (room.messageSender != null && room.messageSender.isNotBlank()) {
                        "${room.messageSender}: ${room.messagePreview}"
                    } else {
                        // This should never happen as we ensure messageSender is always set
                        android.util.Log.w("Andromuks", "RoomListScreen: WARNING - No messageSender for room ${room.name}")
                        android.util.Log.w("Andromuks", "RoomListScreen: Room details - ID: ${room.id}, Preview: '${room.messagePreview}', Sender: '${room.messageSender}'")
                        room.messagePreview
                    }
                    Text(
                        text = messageText,
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

@Composable
fun TabBar(
    currentSection: RoomSection,
    onSectionSelected: (RoomSectionType) -> Unit,
    appViewModel: AppViewModel
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TabButton(
                icon = Icons.Filled.Home,
                label = "Home",
                isSelected = currentSection.type == RoomSectionType.HOME,
                onClick = { onSectionSelected(RoomSectionType.HOME) }
            )
            
            TabButton(
                icon = Icons.Filled.Place,
                label = "Spaces",
                isSelected = currentSection.type == RoomSectionType.SPACES,
                onClick = { onSectionSelected(RoomSectionType.SPACES) }
            )
            
            TabButton(
                icon = Icons.Filled.Person,
                label = "Direct",
                isSelected = currentSection.type == RoomSectionType.DIRECT_CHATS,
                onClick = { onSectionSelected(RoomSectionType.DIRECT_CHATS) },
                badgeCount = if (currentSection.type == RoomSectionType.DIRECT_CHATS) currentSection.unreadCount else 0
            )
            
            TabButton(
                icon = Icons.Filled.Notifications,
                label = "Unread",
                isSelected = currentSection.type == RoomSectionType.UNREAD,
                onClick = { onSectionSelected(RoomSectionType.UNREAD) },
                badgeCount = appViewModel.getUnreadCount()
            )
        }
    }
}

@Composable
fun TabButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0
) {
    val content = @Composable {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) MaterialTheme.colorScheme.primary 
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.primary 
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    
    if (badgeCount > 0) {
        BadgedBox(
            badge = { 
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { 
                    Text("$badgeCount") 
                } 
            }
        ) {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
            ) {
                content()
            }
        }
    } else {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
        ) {
            content()
        }
    }
}

@Composable
fun RoomListContent(
    rooms: List<RoomItem>,
    searchQuery: String,
    appViewModel: AppViewModel,
    authToken: String,
    navController: NavController
) {
    // Handle Android back key when inside a space
    androidx.activity.compose.BackHandler(enabled = appViewModel.currentSpaceId != null) {
        appViewModel.exitSpace()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            bottom = 8.dp
        )
    ) {
        
        val filteredRooms = if (searchQuery.isBlank()) {
            rooms
        } else {
            rooms.filter { room ->
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

@Composable
fun SpacesListContent(
    spaces: List<SpaceItem>,
    searchQuery: String,
    appViewModel: AppViewModel,
    authToken: String,
    navController: NavController
) {
    android.util.Log.d("Andromuks", "SpacesListContent: Displaying ${spaces.size} spaces")
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            bottom = 8.dp
        )
    ) {
        val filteredSpaces = if (searchQuery.isBlank()) {
            spaces
        } else {
            spaces.filter { space ->
                space.name.contains(searchQuery, ignoreCase = true)
            }
        }
        
        items(filteredSpaces.size) { idx ->
            val space = filteredSpaces[idx]
            SpaceListItem(
                space = space,
                isSelected = false,
                onClick = { 
                    appViewModel.enterSpace(space.id)
                },
                homeserverUrl = appViewModel.homeserverUrl,
                authToken = authToken
            )
        }
    }
}