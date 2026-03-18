package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.RoomMemberCache
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.FullImageDialog
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import org.json.JSONObject
import androidx.compose.foundation.shape.RoundedCornerShape
import net.vrkknn.andromuks.utils.navigateToUserInfo
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private fun usernameFromMatrixId(userId: String): String =
    userId.removePrefix("@").substringBefore(":")

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RoomInfoScreen(
    roomId: String,
    navController: NavController,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
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
    var showMembersDialog by remember { mutableStateOf(false) }
    var memberDialogSearchQuery by remember { mutableStateOf("") }
    
    // State for leave room confirmation dialog
    var showLeaveRoomDialog by remember { mutableStateOf(false) }
    var showFullAvatarDialog by remember { mutableStateOf(false) }
    var fullAvatarUrl by remember { mutableStateOf<String?>(null) }
    
    // Request room state when the screen is created
    LaunchedEffect(roomId) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomInfoScreen: Requesting room state for $roomId")
        appViewModel.requestRoomStateWithMembers(roomId) { stateInfo, error ->
            isLoading = false
            if (error != null) {
                errorMessage = error
                android.util.Log.e("Andromuks", "RoomInfoScreen: Error loading room state: $error")
            } else {
                roomStateInfo = stateInfo
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomInfoScreen: Loaded room state successfully")
            }
        }
    }
    
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId).mapValues { (userId, profile) ->
            profile.copy(
                displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(userId)
            )
        }
    }
    var pinnedDirectProfiles by remember(roomId) { mutableStateOf<Map<String, MemberProfile>>(emptyMap()) }
    var requestedPinnedSenders by remember(roomId) { mutableStateOf<Set<String>>(emptySet()) }
    val mergedPinnedMemberMap = remember(memberMap, pinnedDirectProfiles) { memberMap + pinnedDirectProfiles }
    
    // Force recomposition when member map updates (for opportunistic profile loading)
    val memberUpdateCounter = appViewModel.memberUpdateCounter

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room Info") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showLeaveRoomDialog = true }
                    ) {
                        @Suppress("DEPRECATION")
                        Icon(
                            imageVector = Icons.Filled.ExitToApp,
                            contentDescription = "Leave Room",
                            tint = MaterialTheme.colorScheme.error
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
                ExpressiveLoadingIndicator()
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
            val roomInfoScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                    // Whole screen scrolls so long topics / aliases cannot push buttons off-screen
                    .verticalScroll(roomInfoScrollState),
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
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clickable(enabled = roomStateInfo!!.avatarUrl != null) {
                                val avatarUrl = roomStateInfo!!.avatarUrl
                                if (!avatarUrl.isNullOrBlank()) {
                                    val fullUrl = AvatarUtils.getFullImageUrl(
                                        context,
                                        avatarUrl,
                                        appViewModel.homeserverUrl
                                    ) ?: AvatarUtils.getAvatarUrl(
                                        context,
                                        avatarUrl,
                                        appViewModel.homeserverUrl
                                    )
                                    
                                    if (fullUrl != null) {
                                        fullAvatarUrl = fullUrl
                                        showFullAvatarDialog = true
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Full-size avatar unavailable",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Room has no avatar",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                            val sharedKey = "avatar-${roomId}"
                            with(sharedTransitionScope) {
                                AvatarImage(
                                    mxcUrl = roomStateInfo!!.avatarUrl,
                                    homeserverUrl = appViewModel.homeserverUrl,
                                    authToken = appViewModel.authToken,
                                    fallbackText = roomStateInfo!!.name ?: roomId,
                                    size = 120.dp,
                                    userId = roomId,
                                    displayName = roomStateInfo!!.name,
                                    useCircleCache = true, // Match RoomListScreen's cache path for smooth shared element transitions
                                    modifier = Modifier
                                        .sharedElement(
                                            rememberSharedContentState(key = sharedKey),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                            boundsTransform = { _, _ ->
                                                spring(
                                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                                    stiffness = Spring.StiffnessLow
                                                )
                                            },
                                            renderInOverlayDuringTransition = true,
                                            zIndexInOverlay = 1f
                                        )
                                        .clip(CircleShape)
                                )
                            }
                        } else {
                            AvatarImage(
                                mxcUrl = roomStateInfo!!.avatarUrl,
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = appViewModel.authToken,
                                fallbackText = roomStateInfo!!.name ?: roomId,
                                size = 120.dp,
                                userId = roomId,
                                displayName = roomStateInfo!!.name,
                                useCircleCache = true, // Match RoomListScreen's cache path for consistent avatar loading
                                modifier = Modifier.clip(CircleShape)
                            )
                        }
                    }
                }
                
                // Room Topic: cap height + nested scroll so button row stays reachable without
                // scrolling through pages of topic text (get_room_state can return huge m.room.topic)
                roomStateInfo!!.topic?.let { topic ->
                    val topicScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Topic",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = topic,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                // Max height keeps Power Levels / ACL / Pinned / Members visible below
                                .heightIn(max = 220.dp)
                                .verticalScroll(topicScrollState)
                        )
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
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) {
                            Text(
                                text = "Power\nLevels",
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    // Server ACL Button
                    Button(
                        onClick = { showServerAclDialog = true },
                        enabled = roomStateInfo!!.serverAcl != null,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(
                            text = "ACL List",
                            textAlign = TextAlign.Center
                        )
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
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(
                            text = "Pinned",
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Members Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            memberDialogSearchQuery = ""
                            showMembersDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Members")
                    }
                }

                // Technical cache info (always last items)
                run {
                    val cachedEventCountForRoom = RoomTimelineCache.getCachedEventCount(roomId)

                    // RoomMemberCache is the room-scoped member profile cache (used as the fallback by AppViewModel).
                    // This is closer to what you see in UserInfo ("per-room profile") than ProfileCache's flattened cache,
                    // because per-room callbacks don't necessarily populate flattened ProfileCache entries.
                    val roomProfiles = remember(roomId, memberUpdateCounter) {
                        RoomMemberCache.getRoomMembers(roomId)
                    }
                    val roomSpecificProfileCount = roomProfiles.size

                    // Keep this consistent with RoomTimelineCache's estimate:
                    // ~1.5KB per TimelineEvent.
                    val timelineCacheKbForRoom = cachedEventCountForRoom.toDouble() * 1.5

                    // Rough estimate:
                    // Kotlin/JVM strings are UTF-16 internally (2 bytes/char) plus overhead.
                    // We add a small fixed overhead per profile entry to avoid reporting 0KB.
                    val bytesPerChar = 2L
                    val overheadPerProfileBytes = 64L
                    val roomProfilesBytes = roomProfiles.values.sumOf { profile ->
                        val displayNameChars = profile.displayName?.length ?: 0
                        val avatarUrlChars = profile.avatarUrl?.length ?: 0
                        (displayNameChars + avatarUrlChars).toLong() * bytesPerChar + overheadPerProfileBytes
                    }
                    val roomProfilesKbForRoom = roomProfilesBytes.toDouble() / 1024.0

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Technical (cache)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "Room cached events: $cachedEventCountForRoom"
                            )

                            Text(
                                text = "Room profile count: $roomSpecificProfileCount"
                            )

                            Text(
                                text = "Timeline cache (room) est: ${"%.1f".format(timelineCacheKbForRoom)}KB"
                            )

                            Text(
                                text = "Room profiles cache (est): ${"%.1f".format(roomProfilesKbForRoom)}KB"
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Leave Room Confirmation Dialog
    if (showLeaveRoomDialog && roomStateInfo != null) {
        AlertDialog(
            onDismissRequest = { showLeaveRoomDialog = false },
            title = {
                Text("Leave Room")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Are you sure you want to leave")
                    Text(
                        text = roomStateInfo!!.name ?: roomStateInfo!!.canonicalAlias ?: "Unknown Room",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = roomId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLeaveRoomDialog = false
                        appViewModel.leaveRoom(roomId)
                        // Navigate back to room list
                        // Try to pop back to room_list, if not in stack, navigate to it
                        if (!navController.popBackStack("room_list", inclusive = false)) {
                            navController.navigate("room_list") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLeaveRoomDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
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
        // Trigger opportunistic profile loading for pinned event senders
        LaunchedEffect(pinnedEvents, memberUpdateCounter) {
            if (pinnedEvents.isNotEmpty()) {
                val senders = pinnedEvents
                    .mapNotNull { it.timelineEvent?.sender }
                    .distinct()
                    .filter { it.isNotBlank() && it != appViewModel.currentUserId }
                
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomInfoScreen: Triggering opportunistic profile loading for ${senders.size} pinned event senders")
                senders.forEach { sender ->
                    if (!requestedPinnedSenders.contains(sender)) {
                        requestedPinnedSenders = requestedPinnedSenders + sender
                        // 1) Room-specific state (best source for room profile).
                        appViewModel.requestUserProfileOnDemand(sender, roomId)
                        // 2) Deterministic global fallback with direct callback.
                        appViewModel.requestBasicUserProfile(sender) { profile ->
                            if (profile != null) {
                                val displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(sender)
                                pinnedDirectProfiles = pinnedDirectProfiles + (
                                    sender to MemberProfile(
                                        displayName = displayName,
                                        avatarUrl = profile.avatarUrl
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        
        PinnedEventsDialog(
            roomId = roomId,
            isLoading = isPinnedLoading,
            errorMessage = pinnedError,
            pinnedEvents = pinnedEvents,
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            memberMap = mergedPinnedMemberMap,
            myUserId = appViewModel.currentUserId,
            appViewModel = appViewModel,
            navController = navController,
            onRefreshPinnedEvents = {
                // First refresh the room state to get updated pinned event IDs
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomInfoScreen: Refreshing room state after unpin")
                appViewModel.requestRoomStateWithMembers(roomId) { stateInfo, error ->
                    if (error != null) {
                        android.util.Log.e("Andromuks", "RoomInfoScreen: Error refreshing room state: $error")
                        pinnedError = error
                        isPinnedLoading = false
                    } else {
                        // Update roomStateInfo with fresh data
                        roomStateInfo = stateInfo
                        val pinnedIds = stateInfo?.pinnedEventIds ?: emptyList()
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomInfoScreen: Refreshed room state, found ${pinnedIds.size} pinned events")
                        
                        // Reload pinned events with updated IDs
                        if (pinnedIds.isNotEmpty()) {
                            pinnedEvents = emptyList()
                            isPinnedLoading = true
                            pinnedError = null
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
                            // No pinned events left
                            pinnedEvents = emptyList()
                            pinnedError = null
                            isPinnedLoading = false
                        }
                    }
                }
            },
            onDismiss = {
                pinnedEvents = emptyList()
                pinnedError = null
                isPinnedLoading = false
                showPinnedDialog = false
                pinnedDirectProfiles = emptyMap()
                requestedPinnedSenders = emptySet()
            }
        )
    }
    
    if (showFullAvatarDialog && fullAvatarUrl != null) {
        FullImageDialog(
            imageUrl = fullAvatarUrl!!,
            authToken = appViewModel.authToken,
            onDismiss = { showFullAvatarDialog = false },
            contentDescription = roomStateInfo?.name ?: roomId
        )
    }
    
    // Members Dialog
    if (showMembersDialog && roomStateInfo != null) {
        MembersDialog(
            members = roomStateInfo!!.members,
            powerLevels = roomStateInfo!!.powerLevels,
            memberMap = memberMap,
            memberSearchQuery = memberDialogSearchQuery,
            onSearchQueryChange = { memberDialogSearchQuery = it },
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            navController = navController,
            roomId = roomId,
            onDismiss = {
                showMembersDialog = false
                memberDialogSearchQuery = ""
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
    var hasTimeout = false

    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "loadPinnedEvents: Loading ${pinnedIds.size} pinned events for room $roomId")

    pinnedIds.forEach { eventId ->
        appViewModel.getEvent(roomId, eventId) { timelineEvent ->
            synchronized(results) {
                results.add(PinnedEventItem(eventId, timelineEvent))
                remaining -= 1
                
                if (timelineEvent == null) {
                    hasTimeout = true
                    android.util.Log.w("Andromuks", "loadPinnedEvents: Failed to load event $eventId")
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "loadPinnedEvents: Successfully loaded event $eventId")
                }

                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "loadPinnedEvents: Progress: ${pinnedIds.size - remaining}/${pinnedIds.size} events loaded")

                if (remaining == 0) {
                    // Get all successfully loaded events and sort by timestamp (most recent first)
                    val loadedEvents = results.filter { it.timelineEvent != null }
                        .sortedByDescending { it.timelineEvent!!.timestamp }
                    
                    // Add failed events at the end (preserve original order for failed ones)
                    val failedEvents = results.filter { it.timelineEvent == null }
                        .sortedBy { pinnedIds.indexOf(it.eventId) }
                    
                    // Combine: most recent first, then failed events
                    val ordered = loadedEvents + failedEvents
                    
                    // Set appropriate error message
                    val finalErrorMessage = when {
                        hasTimeout && results.all { it.timelineEvent == null } -> "All pinned events failed to load (timeout or not found)"
                        hasTimeout -> "Some pinned events failed to load (timeout or not found)"
                        else -> null
                    }
                    
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "loadPinnedEvents: Completed loading pinned events. Success: ${results.count { it.timelineEvent != null }}/${pinnedIds.size}")
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "loadPinnedEvents: Events ordered by timestamp (most recent first)")
                    onResult(ordered, finalErrorMessage)
                }
            }
        }
    }
}

@Composable
private fun PinnedEventsDialog(
    roomId: String,
    isLoading: Boolean,
    errorMessage: String?,
    pinnedEvents: List<PinnedEventItem>,
    homeserverUrl: String,
    authToken: String,
    memberMap: Map<String, MemberProfile>,
    myUserId: String?,
    appViewModel: AppViewModel,
    navController: NavController,
    onRefreshPinnedEvents: () -> Unit,
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
                        ExpressiveLoadingIndicator()
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
                                roomId = roomId,
                                pinnedItem = pinnedItem,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                memberMap = memberMap,
                                myUserId = myUserId,
                                appViewModel = appViewModel,
                                navController = navController,
                                onRefreshPinnedEvents = onRefreshPinnedEvents,
                                onDismiss = onDismiss
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
private fun MembersDialog(
    members: List<RoomMember>,
    powerLevels: PowerLevelsInfo?,
    memberMap: Map<String, MemberProfile>,
    memberSearchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    homeserverUrl: String,
    authToken: String,
    navController: NavController,
    roomId: String,
    onDismiss: () -> Unit
) {
    // Sort and filter members
    val sortedAndFilteredMembers = remember(members, powerLevels, memberMap, memberSearchQuery) {
        // First filter by search query
        val filtered = if (memberSearchQuery.isBlank()) {
            members
        } else {
            members.filter { member ->
                val roomDisplayName = member.displayName?.lowercase() ?: ""
                val globalDisplayName = memberMap[member.userId]?.displayName?.lowercase() ?: ""
                val username = usernameFromMatrixId(member.userId).lowercase()
                val searchLower = memberSearchQuery.lowercase()
                
                roomDisplayName.contains(searchLower) ||
                globalDisplayName.contains(searchLower) ||
                username.contains(searchLower) ||
                member.userId.lowercase().contains(searchLower)
            }
        }
        
        // Separate BAN and LEAVE members
        val activeMembers = filtered.filter { it.membership != "ban" && it.membership != "leave" }
        val banLeaveMembers = filtered.filter { it.membership == "ban" || it.membership == "leave" }
        
        // Sort active members: by power level (descending), then by room-specific displayname, then global displayname, then username
        val sortedActive = activeMembers.sortedWith(compareBy<RoomMember>(
            { -(powerLevels?.users?.get(it.userId) ?: powerLevels?.usersDefault ?: 0) }, // Power level descending
            { it.displayName?.lowercase() ?: "" }, // Room-specific displayname
            { memberMap[it.userId]?.displayName?.lowercase() ?: "" }, // Global displayname
            { usernameFromMatrixId(it.userId).lowercase() } // Username
        ))
        
        // Sort ban/leave members: alphabetically by room-specific displayname, then global displayname, then username
        val sortedBanLeave = banLeaveMembers.sortedWith(compareBy<RoomMember>(
            { it.displayName?.lowercase() ?: "" }, // Room-specific displayname
            { memberMap[it.userId]?.displayName?.lowercase() ?: "" }, // Global displayname
            { usernameFromMatrixId(it.userId).lowercase() } // Username
        ))
        
        // Return active members first, then ban/leave members at the bottom
        sortedActive + sortedBanLeave
    }
    
    val joinedCount = members.count { it.membership == "join" }
    val invitedCount = members.count { it.membership == "invite" }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Column {
                Text("Room Members (${members.size})")
                if (invitedCount > 0) {
                    Text(
                        text = "$joinedCount joined, $invitedCount invited",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search box
                TextField(
                    value = memberSearchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search members...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search"
                        )
                    }
                )
                
                // Member list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (sortedAndFilteredMembers.isEmpty()) {
                        item {
                            Text(
                                text = if (memberSearchQuery.isBlank()) "No members found" else "No members match your search",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        items(sortedAndFilteredMembers) { member ->
                            RoomMemberItem(
                                member = member,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                powerLevel = powerLevels?.users?.get(member.userId),
                                onUserClick = { userId ->
                                    navController.navigateToUserInfo(userId, roomId)
                                }
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
    roomId: String,
    pinnedItem: PinnedEventItem,
    homeserverUrl: String,
    authToken: String,
    memberMap: Map<String, MemberProfile>,
    myUserId: String?,
    appViewModel: AppViewModel,
    navController: NavController,
    onRefreshPinnedEvents: () -> Unit,
    onDismiss: () -> Unit
) {
    val event = pinnedItem.timelineEvent
    var showUnpinDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Debug logging for profile loading
    LaunchedEffect(event?.sender, memberMap) {
        if (event?.sender != null) {
            val profile = memberMap[event.sender]
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "PinnedEventItemView: Event sender: ${event.sender}, Profile found: ${profile != null}, DisplayName: ${profile?.displayName}")
        }
    }

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
                Box(
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            // Navigate to event context screen
                            val encodedRoomId = java.net.URLEncoder.encode(event.roomId, "UTF-8")
                            val encodedEventId = java.net.URLEncoder.encode(event.eventId, "UTF-8")
                            navController.navigate("event_context/$encodedRoomId/$encodedEventId")
                            onDismiss() // Dismiss the pinned events dialog
                        },
                        onLongClick = {
                            // Show unpin dialog on long press
                            showUnpinDialog = true
                        }
                    )
                ) {
                    TimelineEventItem(
                        event = event,
                        timelineEvents = listOf(event),
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        userProfileCache = memberMap,
                        isMine = myUserId != null && event.sender == myUserId,
                        myUserId = myUserId,
                        appViewModel = appViewModel,
                        onUserClick = { userId ->
                            // Navigate to user info and dismiss dialog
                            navController.navigateToUserInfo(userId, event.roomId)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
    
    // Unpin confirmation dialog
    if (showUnpinDialog && event != null) {
        AlertDialog(
            onDismissRequest = { showUnpinDialog = false },
            title = { Text("Unpin Event") },
            text = {
                Column {
                    Text("Are you sure you want to unpin this event?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = event.eventId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnpinDialog = false
                        appViewModel.pinUnpinEvent(roomId, event.eventId, pin = false)
                        // Refresh the pinned events list after a short delay to allow get_room_state to complete
                        coroutineScope.launch {
                            delay(500) // Wait for get_room_state response
                            onRefreshPinnedEvents()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Unpin")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
    val displayName = member.displayName ?: usernameFromMatrixId(member.userId)
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
            fallbackText = displayName.take(1),
            size = 40.dp,
            userId = member.userId,
            displayName = displayName
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
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
        
        // Show membership status badge if not joined
        if (member.membership != "join") {
            Surface(
                color = when (member.membership) {
                    "invite" -> MaterialTheme.colorScheme.tertiaryContainer
                    "ban" -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = member.membership.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (member.membership) {
                        "invite" -> MaterialTheme.colorScheme.onTertiaryContainer
                        "ban" -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
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
 * Extract topic text from MSC-style content: m.topic.m.text[0].body
 */
private fun extractTopicFromMTopicContent(content: JSONObject?): String? {
    if (content == null) return null
    val mTopic = content.optJSONObject("m.topic") ?: return null
    val mText = mTopic.optJSONArray("m.text") ?: return null
    if (mText.length() == 0) return null
    val first = mText.optJSONObject(0) ?: return null
    return first.optString("body").takeIf { it.isNotBlank() }
}

/**
 * Parse room state response from the server
 */
fun parseRoomStateResponse(data: Any): RoomStateInfo? {
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "parseRoomStateResponse: Parsing room state response")
    
    try {
        val eventsArray = when (data) {
            is org.json.JSONArray -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "parseRoomStateResponse: Received JSONArray with ${data.length()} events")
                data
            }
            is List<*> -> {
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "parseRoomStateResponse: Received List with ${data.size} events, converting to JSONArray")
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
        
        // Use a map to deduplicate members by userId (keep latest state for each user)
        val membersMap = mutableMapOf<String, RoomMember>()
        var totalMemberEvents = 0
        var joinedMemberEvents = 0
        
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
                    // Plain string topic (common)
                    topic = content?.optString("topic")?.takeIf { it.isNotBlank() }
                    // Fallback: MSC-style m.topic.m.text[].body when topic key missing or empty
                    if (topic.isNullOrBlank()) {
                        topic = extractTopicFromMTopicContent(content)
                    }
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
                    totalMemberEvents++
                    val userId = event.optString("state_key", "")
                    if (userId.isNotEmpty()) {
                        val displayName = content?.optString("displayname")
                        val memberAvatarUrl = content?.optString("avatar_url")
                        val membership = content?.optString("membership", "leave") ?: "leave"
                        
                        // Include ALL members (joined, invited, left, banned) to show complete room state
                        // Use map to deduplicate by userId (keep latest state for each user)
                        if (membership == "join") {
                            joinedMemberEvents++
                        }
                        
                        // Store all members regardless of membership status
                        // This allows RoomInfo screen to show invited users, etc.
                        membersMap[userId] = RoomMember(
                            userId = userId,
                            displayName = displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(userId),
                            avatarUrl = memberAvatarUrl?.takeIf { it.isNotBlank() },
                            membership = membership
                        )
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
        
        // Convert map to list and sort members alphabetically by display name
        // Sort by membership status first (joined first, then invited, then others), then by name
        members.addAll(membersMap.values)
        members.sortWith(compareBy<RoomMember>(
            { it.membership != "join" }, // Joined members first
            { it.membership != "invite" }, // Then invited
            { it.displayName?.lowercase() ?: it.userId.lowercase() } // Then alphabetically
        ))
        
        val invitedCount = members.count { it.membership == "invite" }
        val leftCount = members.count { it.membership == "leave" }
        val bannedCount = members.count { it.membership == "ban" }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "parseRoomStateResponse: Parsed ${members.size} total members from $totalMemberEvents member events: $joinedMemberEvents joined, $invitedCount invited, $leftCount left, $bannedCount banned")
        
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

