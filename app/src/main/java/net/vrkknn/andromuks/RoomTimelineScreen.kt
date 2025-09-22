package net.vrkknn.andromuks

import android.util.Log
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import android.content.Context

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomTimelineScreen(
    roomId: String,
    roomName: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val sharedPreferences = remember(context) { context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE) }
    val authToken = remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val homeserverUrl = appViewModel.homeserverUrl
    Log.d("Andromuks", "RoomTimelineScreen: appViewModel instance: $appViewModel")
    val timelineEvents = appViewModel.timelineEvents
    val isLoading = appViewModel.isTimelineLoading

    // Build user profile cache from m.room.member events
    val userProfileCache = remember(timelineEvents) {
        val map = mutableMapOf<String, Pair<String?, String?>>() // userId -> (displayName, avatarUrl)
        for (event in timelineEvents) {
            if (event.type == "m.room.member") {
                val userId = event.stateKey ?: event.sender
                val content = event.content
                val displayName = content?.optString("displayname", null)
                val avatarUrl = content?.optString("avatar_url", null)
                map[userId] = Pair(displayName, avatarUrl)
            }
        }
        map
    }

    LaunchedEffect(roomId) {
        Log.d("Andromuks", "RoomTimelineScreen: Loading timeline for room: $roomId")
        // Request room state and timeline
        appViewModel.requestRoomTimeline(roomId)
    }
    
    AndromuksTheme {
        Surface {
            Column(modifier = modifier.fillMaxSize()) {
                TopAppBar(
                    title = { 
                        Text(
                            text = roomName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { navController.popBackStack() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
                
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading timeline...")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        items(timelineEvents) { event ->
                            TimelineEventItem(
                                event = event,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                userProfileCache = appViewModel.getMemberMap(roomId)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineEventItem(
    event: TimelineEvent,
    homeserverUrl: String,
    authToken: String,
    userProfileCache: Map<String, Pair<String?, String?>>
) {
    val context = LocalContext.current
    // Lookup display name and avatar from cache
    val (displayName, avatarUrl) = userProfileCache[event.sender] ?: Pair(null, null)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Sender avatar
        AvatarImage(
            mxcUrl = avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = (displayName ?: event.sender).take(1),
            size = 32.dp
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Event content
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(
                    text = displayName ?: event.sender,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatTimestamp(event.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            when (event.type) {
                "m.room.message" -> {
                    val content = event.content
                    val body = content?.optString("body", "")
                    val msgtype = content?.optString("msgtype", "")
                    
                    Text(
                        text = body ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                "m.room.member" -> {
                    val content = event.content
                    val membership = content?.optString("membership", "")
                    val displayname = content?.optString("displayname", "")
                    
                    Text(
                        text = when (membership) {
                            "join" -> "$displayname joined"
                            "leave" -> "$displayname left"
                            "invite" -> "$displayname was invited"
                            else -> "Membership change"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text(
                        text = "Event type: ${event.type}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(date)
}
