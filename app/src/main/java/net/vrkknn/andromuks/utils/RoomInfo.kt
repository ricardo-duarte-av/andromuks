package net.vrkknn.andromuks.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem
import net.vrkknn.andromuks.ui.components.AvatarImage
import org.json.JSONObject
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Data class to hold complete room state information
 */
data class RoomStateInfo(
    val roomId: String,
    val name: String?,
    val topic: String?,
    val avatarUrl: String?,
    val canonicalAlias: String?,
    val altAliases: List<String>,
    val pinnedEventIds: List<String>,
    val creator: String?,
    val roomVersion: String?,
    val historyVisibility: String?,
    val joinRule: String?,
    val members: List<RoomMember>,
    val powerLevels: PowerLevelsInfo?,
    val serverAcl: ServerAclInfo?,
    val parentSpace: String?,
    val urlPreviewsDisabled: Boolean?
)

/**
 * Data class for a room member
 */
data class RoomMember(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val membership: String
)

/**
 * Data class for power levels information
 */
data class PowerLevelsInfo(
    val users: Map<String, Int>,
    val usersDefault: Int,
    val events: Map<String, Int>,
    val eventsDefault: Int,
    val stateDefault: Int,
    val ban: Int,
    val kick: Int,
    val redact: Int,
    val invite: Int
)

/**
 * Data class for server ACL information
 */
data class ServerAclInfo(
    val allow: List<String>,
    val deny: List<String>,
    val allowIpLiterals: Boolean
)

/**
 * Room Info Screen - displays detailed information about a room
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomInfoScreen(
    roomId: String,
    navController: NavController,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // State to hold the room info
    var roomStateInfo by remember { mutableStateOf<RoomStateInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pinnedEvents by remember { mutableStateOf<List<PinnedEventItem>>(emptyList()) }
    var isPinnedLoading by remember { mutableStateOf(false) }
    var pinnedError by remember { mutableStateOf<String?>(null) }
    var showPinnedDialog by remember { mutableStateOf(false) }
    
    // State for dialog visibility
    var showPowerLevelsDialog by remember { mutableStateOf(false) }
    var showServerAclDialog by remember { mutableStateOf(false) }
    
    // State for member search
    var showMemberSearch by remember { mutableStateOf(false) }
    var memberSearchQuery by remember { mutableStateOf("") }
    
    // State for topic expansion
    var isTopicExpanded by remember { mutableStateOf(false) }
    
    // Request room state when the screen is created
    LaunchedEffect(roomId) {
        android.util.Log.d("Andromuks", "RoomInfoScreen: Requesting room state for $roomId")
        appViewModel.requestRoomStateWithMembers(roomId) { stateInfo, error ->
            isLoading = false
            if (error != null) {
                errorMessage = error
                android.util.Log.e("Andromuks", "RoomInfoScreen: Error loading room state: $error")
            } else {
                roomStateInfo = stateInfo
                android.util.Log.d("Andromuks", "RoomInfoScreen: Loaded room state successfully")
            }
        }
    }
    
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room Info") }
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
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Error: $errorMessage",
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else if (roomStateInfo != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Room ID
                Text(
                    text = roomStateInfo!!.roomId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Room Display Name and Canonical Alias
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    roomStateInfo!!.name?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    // Canonical Alias directly below room name
                    roomStateInfo!!.canonicalAlias?.let { alias ->
                        Text(
                            text = alias,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                
                // Room Avatar
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    AvatarImage(
                        mxcUrl = roomStateInfo!!.avatarUrl,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        fallbackText = roomStateInfo!!.name ?: roomId,
                        size = 120.dp,
                        userId = roomId,
                        displayName = roomStateInfo!!.name
                    )
                }
                
                // Room Topic (collapsible)
                roomStateInfo!!.topic?.let { topic ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isTopicExpanded = !isTopicExpanded }
                    ) {
                        Text(
                            text = "Topic",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = topic,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (isTopicExpanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (topic.length > 100 || topic.lines().size > 2) {
                            Text(
                                text = if (isTopicExpanded) "Show less" else "Show more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                
                // Power Levels and ACL Buttons side by side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Power Levels Button
                    if (roomStateInfo!!.powerLevels != null) {
                        Button(
                            onClick = { showPowerLevelsDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Power Levels")
                        }
                    }
                    
                    // Server ACL Button
                    Button(
                        onClick = { showServerAclDialog = true },
                        enabled = roomStateInfo!!.serverAcl != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ACL List")
                    }

                    // Pinned Events Button
                    Button(
                        onClick = {
                            val pinnedIds = roomStateInfo!!.pinnedEventIds
                            if (pinnedIds.isNotEmpty()) {
                                pinnedEvents = emptyList()
                                isPinnedLoading = true
                                pinnedError = null
                                showPinnedDialog = true
                                loadPinnedEvents(
                                    pinnedIds = pinnedIds,
                                    roomId = roomId,
                                    appViewModel = appViewModel,
                                    onResult = { events, error ->
                                        pinnedEvents = events
                                        pinnedError = error
                                        isPinnedLoading = false
                                    }
                                )
                            } else {
                                pinnedEvents = emptyList()
                                pinnedError = null
                                showPinnedDialog = true
                            }
                        },
                        enabled = roomStateInfo!!.pinnedEventIds.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pinned")
                    }
                }
                
                // Member List Frame with Search - takes remaining space
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Divider()
                    
                    // Header with member count and search button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Room Members (${roomStateInfo!!.members.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        IconButton(onClick = { 
                            showMemberSearch = !showMemberSearch
                            if (!showMemberSearch) memberSearchQuery = ""
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search Members",
                                tint = if (showMemberSearch) MaterialTheme.colorScheme.primary 
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Search box (shown when search is active)
                    if (showMemberSearch) {
                        TextField(
                            value = memberSearchQuery,
                            onValueChange = { memberSearchQuery = it },
                            placeholder = { Text("Search members...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    }
                    
                    // Scrollable member list in a card frame - fills remaining space
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        // Filter members based on search query
                        val filteredMembers = if (memberSearchQuery.isBlank()) {
                            roomStateInfo!!.members
                        } else {
                            roomStateInfo!!.members.filter { member ->
                                (member.displayName?.contains(memberSearchQuery, ignoreCase = true) ?: false) ||
                                member.userId.contains(memberSearchQuery, ignoreCase = true)
                            }
                        }
                        
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredMembers) { member ->
                                RoomMemberItem(
                                    member = member,
                                    homeserverUrl = appViewModel.homeserverUrl,
                                    authToken = appViewModel.authToken,
                                    powerLevel = roomStateInfo!!.powerLevels?.users?.get(member.userId),
                                    onUserClick = { userId ->
                                        navController.navigate("user_info/${java.net.URLEncoder.encode(userId, "UTF-8")}")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Power Levels Dialog
    if (showPowerLevelsDialog && roomStateInfo?.powerLevels != null) {
        PowerLevelsDialog(
            powerLevels = roomStateInfo!!.powerLevels!!,
            onDismiss = { showPowerLevelsDialog = false }
        )
    }
    
    // Server ACL Dialog
    if (showServerAclDialog && roomStateInfo?.serverAcl != null) {
        ServerAclDialog(
            serverAcl = roomStateInfo!!.serverAcl!!,
            onDismiss = { showServerAclDialog = false }
        )
    }

    // Pinned Events Dialog
    if (showPinnedDialog) {
        PinnedEventsDialog(
            isLoading = isPinnedLoading,
            errorMessage = pinnedError,
            pinnedEvents = pinnedEvents,
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            memberMap = memberMap,
            myUserId = appViewModel.currentUserId,
            onDismiss = {
                pinnedEvents = emptyList()
                pinnedError = null
                isPinnedLoading = false
                showPinnedDialog = false
            }
        )
    }
}

data class PinnedEventItem(
    val eventId: String,
    val timelineEvent: net.vrkknn.andromuks.TimelineEvent?
)

private fun loadPinnedEvents(
    pinnedIds: List<String>,
    roomId: String,
    appViewModel: AppViewModel,
    onResult: (List<PinnedEventItem>, String?) -> Unit
) {
    if (pinnedIds.isEmpty()) {
        onResult(emptyList(), null)
        return
    }

    val results = mutableListOf<PinnedEventItem>()
    var remaining = pinnedIds.size
    var errorMessage: String? = null

    pinnedIds.forEach { eventId ->
        appViewModel.getEvent(roomId, eventId) { timelineEvent ->
            synchronized(results) {
                results.add(PinnedEventItem(eventId, timelineEvent))
                remaining -= 1
                if (timelineEvent == null) {
                    errorMessage = errorMessage ?: "Some events could not be loaded"
                }

                if (remaining == 0) {
                    // Preserve original order based on pinnedIds list
                    val ordered = pinnedIds.map { id ->
                        results.find { it.eventId == id } ?: PinnedEventItem(id, null)
                    }
                    onResult(ordered, errorMessage)
                }
            }
        }
    }
}

@Composable
private fun PinnedEventsDialog(
    isLoading: Boolean,
    errorMessage: String?,
    pinnedEvents: List<PinnedEventItem>,
    homeserverUrl: String,
    authToken: String,
    memberMap: Map<String, MemberProfile>,
    myUserId: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pinned Events") },
        text = {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null && pinnedEvents.isEmpty() -> {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                pinnedEvents.isEmpty() -> {
                    Text("No pinned events found.")
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pinnedEvents) { pinnedItem ->
                            PinnedEventItemView(
                                pinnedItem = pinnedItem,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                memberMap = memberMap,
                                myUserId = myUserId
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun PinnedEventItemView(
    pinnedItem: PinnedEventItem,
    homeserverUrl: String,
    authToken: String,
    memberMap: Map<String, MemberProfile>,
    myUserId: String?
) {
    val event = pinnedItem.timelineEvent

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = event?.eventId ?: pinnedItem.eventId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (event == null) {
                Text(
                    text = "Event data is not available (404)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                TimelineEventItem(
                    event = event,
                    timelineEvents = listOf(event),
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    userProfileCache = memberMap,
                    isMine = myUserId != null && event.sender == myUserId,
                    myUserId = myUserId
                )
            }
        }
    }
}

/**
 * Composable for a single room member item
 */
@Composable
fun RoomMemberItem(
    member: RoomMember,
    homeserverUrl: String,
    authToken: String,
    powerLevel: Int?,
    onUserClick: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(member.userId) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            mxcUrl = member.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = (member.displayName ?: member.userId).take(1),
            size = 40.dp,
            userId = member.userId,
            displayName = member.displayName
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.displayName ?: member.userId,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = member.userId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Show power level badge if user has special powers
        if (powerLevel != null && powerLevel > 0) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "PL: $powerLevel",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Dialog to display power levels information
 */
@Composable
fun PowerLevelsDialog(
    powerLevels: PowerLevelsInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Power Levels") },
        text = {
            LazyColumn {
                item {
                    Text(
                        text = "Default Levels",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Users Default: ${powerLevels.usersDefault}")
                    Text("Events Default: ${powerLevels.eventsDefault}")
                    Text("State Default: ${powerLevels.stateDefault}")
                    Text("Ban: ${powerLevels.ban}")
                    Text("Kick: ${powerLevels.kick}")
                    Text("Redact: ${powerLevels.redact}")
                    Text("Invite: ${powerLevels.invite}")
                    
                    if (powerLevels.users.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "User Power Levels",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                items(powerLevels.users.entries.toList()) { (userId, level) ->
                    Text("$userId: $level")
                }
                
                item {
                    if (powerLevels.events.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Event Power Levels",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                items(powerLevels.events.entries.toList()) { (eventType, level) ->
                    Text("$eventType: $level")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Dialog to display server ACL information
 */
@Composable
fun ServerAclDialog(
    serverAcl: ServerAclInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server ACL") },
        text = {
            LazyColumn {
                item {
                    Text("Allow IP Literals: ${serverAcl.allowIpLiterals}")
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                item {
                    Text(
                        text = "Allowed Servers (${serverAcl.allow.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                items(serverAcl.allow) { server ->
                    Text(server, style = MaterialTheme.typography.bodySmall)
                }
                
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Denied Servers (${serverAcl.deny.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                items(serverAcl.deny) { server ->
                    Text(server, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Parse room state response from the server
 */
fun parseRoomStateResponse(data: Any): RoomStateInfo? {
    android.util.Log.d("Andromuks", "parseRoomStateResponse: Parsing room state response")
    
    try {
        val eventsArray = when (data) {
            is org.json.JSONArray -> data
            is List<*> -> {
                // Convert list to JSONArray
                val jsonArray = org.json.JSONArray()
                data.forEach { jsonArray.put(it) }
                jsonArray
            }
            else -> {
                android.util.Log.e("Andromuks", "parseRoomStateResponse: Unexpected data type: ${data.javaClass}")
                return null
            }
        }
        
        var roomId = ""
        var name: String? = null
        var topic: String? = null
        var avatarUrl: String? = null
        var canonicalAlias: String? = null
        val altAliases = mutableListOf<String>()
        val pinnedEventIds = mutableListOf<String>()
        var creator: String? = null
        var roomVersion: String? = null
        var historyVisibility: String? = null
        var joinRule: String? = null
        val members = mutableListOf<RoomMember>()
        var powerLevels: PowerLevelsInfo? = null
        var serverAcl: ServerAclInfo? = null
        var parentSpace: String? = null
        var urlPreviewsDisabled: Boolean? = null
        
        for (i in 0 until eventsArray.length()) {
            val event = eventsArray.optJSONObject(i) ?: continue
            
            // Extract room ID from first event
            if (roomId.isEmpty()) {
                roomId = event.optString("room_id", "")
            }
            
            val type = event.optString("type", "")
            val content = event.optJSONObject("content")
            
            when (type) {
                "m.room.name" -> {
                    name = content?.optString("name")
                }
                "m.room.topic" -> {
                    topic = content?.optString("topic")
                }
                "m.room.avatar" -> {
                    avatarUrl = content?.optString("url")
                }
                "m.room.canonical_alias" -> {
                    canonicalAlias = content?.optString("alias")
                    val altAliasesArray = content?.optJSONArray("alt_aliases")
                    if (altAliasesArray != null) {
                        for (j in 0 until altAliasesArray.length()) {
                            altAliases.add(altAliasesArray.optString(j))
                        }
                    }
                }
                "m.room.pinned_events" -> {
                    pinnedEventIds.clear()
                    val pinnedArray = content?.optJSONArray("pinned")
                    if (pinnedArray != null) {
                        for (j in 0 until pinnedArray.length()) {
                            val eventId = pinnedArray.optString(j)
                            if (!eventId.isNullOrBlank()) {
                                pinnedEventIds.add(eventId)
                            }
                        }
                    }
                }
                "m.room.create" -> {
                    creator = event.optString("sender")
                    roomVersion = content?.optString("room_version")
                }
                "m.room.history_visibility" -> {
                    historyVisibility = content?.optString("history_visibility")
                }
                "m.room.join_rules" -> {
                    joinRule = content?.optString("join_rule")
                }
                "m.room.member" -> {
                    val userId = event.optString("state_key", "")
                    if (userId.isNotEmpty()) {
                        val displayName = content?.optString("displayname")
                        val memberAvatarUrl = content?.optString("avatar_url")
                        val membership = content?.optString("membership", "leave")
                        
                        // Only include joined members
                        if (membership == "join") {
                            members.add(
                                RoomMember(
                                    userId = userId,
                                    displayName = displayName?.takeIf { it.isNotBlank() },
                                    avatarUrl = memberAvatarUrl?.takeIf { it.isNotBlank() },
                                    membership = membership
                                )
                            )
                        }
                    }
                }
                "m.room.power_levels" -> {
                    val usersObj = content?.optJSONObject("users")
                    val usersMap = mutableMapOf<String, Int>()
                    if (usersObj != null) {
                        val keys = usersObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            usersMap[key] = usersObj.optInt(key, 0)
                        }
                    }
                    
                    val eventsObj = content?.optJSONObject("events")
                    val eventsMap = mutableMapOf<String, Int>()
                    if (eventsObj != null) {
                        val keys = eventsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            eventsMap[key] = eventsObj.optInt(key, 0)
                        }
                    }
                    
                    powerLevels = PowerLevelsInfo(
                        users = usersMap,
                        usersDefault = content?.optInt("users_default", 0) ?: 0,
                        events = eventsMap,
                        eventsDefault = content?.optInt("events_default", 0) ?: 0,
                        stateDefault = content?.optInt("state_default", 50) ?: 50,
                        ban = content?.optInt("ban", 50) ?: 50,
                        kick = content?.optInt("kick", 50) ?: 50,
                        redact = content?.optInt("redact", 50) ?: 50,
                        invite = content?.optInt("invite", 50) ?: 50
                    )
                }
                "m.room.server_acl" -> {
                    val allowArray = content?.optJSONArray("allow")
                    val allow = mutableListOf<String>()
                    if (allowArray != null) {
                        for (j in 0 until allowArray.length()) {
                            allow.add(allowArray.optString(j))
                        }
                    }
                    
                    val denyArray = content?.optJSONArray("deny")
                    val deny = mutableListOf<String>()
                    if (denyArray != null) {
                        for (j in 0 until denyArray.length()) {
                            deny.add(denyArray.optString(j))
                        }
                    }
                    
                    serverAcl = ServerAclInfo(
                        allow = allow,
                        deny = deny,
                        allowIpLiterals = content?.optBoolean("allow_ip_literals", false) ?: false
                    )
                }
                "m.space.parent" -> {
                    parentSpace = event.optString("state_key")
                }
                "org.matrix.room.preview_urls" -> {
                    urlPreviewsDisabled = content?.optBoolean("disable", false)
                }
            }
        }
        
        // Sort members alphabetically by display name
        members.sortBy { it.displayName?.lowercase() ?: it.userId.lowercase() }
        
        return RoomStateInfo(
            roomId = roomId,
            name = name,
            topic = topic,
            avatarUrl = avatarUrl,
            canonicalAlias = canonicalAlias,
            altAliases = altAliases,
            pinnedEventIds = pinnedEventIds,
            creator = creator,
            roomVersion = roomVersion,
            historyVisibility = historyVisibility,
            joinRule = joinRule,
            members = members,
            powerLevels = powerLevels,
            serverAcl = serverAcl,
            parentSpace = parentSpace,
            urlPreviewsDisabled = urlPreviewsDisabled
        )
    } catch (e: Exception) {
        android.util.Log.e("Andromuks", "parseRoomStateResponse: Error parsing room state", e)
        return null
    }
}

