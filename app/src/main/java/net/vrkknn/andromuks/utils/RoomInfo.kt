package net.vrkknn.andromuks.utils

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.RoomMemberCache
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.theme.scaledTweenMs
import net.vrkknn.andromuks.utils.navigateToUserInfo

// internal, not private: parseRoomMembers in RoomStateParsing.kt shares it rather than adding a
// fourth copy of this one-liner (RoomListScreen and UserInfo still carry their own).
internal fun usernameFromMatrixId(userId: String): String = userId.removePrefix("@").substringBefore(":")

/**
 * Data class for a room member
 */
data class RoomMember(val userId: String, val displayName: String?, val avatarUrl: String?, val membership: String)

// There were two data classes called PowerLevelsInfo — this one and net.vrkknn.andromuks's — with
// the same fields in a different order and different defaults, populated by different parsers.
// Aliased onto the surviving one so this file's references keep working.
private typealias PowerLevelsInfo = net.vrkknn.andromuks.PowerLevelsInfo

// ServerAclInfo lives in RoomItem.kt alongside RoomState, which absorbed this screen's state model.
// Aliased so this file's references stay short.
private typealias ServerAclInfo = net.vrkknn.andromuks.ServerAclInfo

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
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // State to hold the room info
    var roomState by remember { mutableStateOf<net.vrkknn.andromuks.RoomState?>(null) }
    // Members are not cached anywhere — they come back with the response and live only here.
    var roomMembers by remember { mutableStateOf<List<RoomMember>>(emptyList()) }
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
    var isMembersRefreshing by remember { mutableStateOf(false) }
    var showPushRulesDialog by remember { mutableStateOf(false) }
    var memberDialogSearchQuery by remember { mutableStateOf("") }

    // State for leave room confirmation dialog
    var showLeaveRoomDialog by remember { mutableStateOf(false) }
    var showFullAvatarDialog by remember { mutableStateOf(false) }
    var fullAvatarMxc by remember { mutableStateOf<String?>(null) }

    // Request room state when the screen is created
    LaunchedEffect(roomId) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomInfoScreen: Requesting room state for $roomId")
        appViewModel.requestRoomStateWithMembers(roomId) { state, members, error ->
            isLoading = false
            if (error != null) {
                errorMessage = error
                android.util.Log.e("Andromuks", "RoomInfoScreen: Error loading room state: $error")
            } else {
                roomState = state
                roomMembers = members
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomInfoScreen: Loaded room state successfully")
            }
        }
    }

    val memberMap = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId).mapValues { (userId, profile) ->
            profile.copy(
                displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(userId),
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showLeaveRoomDialog = true },
                    ) {
                        @Suppress("DEPRECATION")
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Leave Room",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                ExpressiveLoadingIndicator()
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Error: $errorMessage",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (roomState != null) {
            // Heroes fallback for nameless / avatarless rooms (typically DMs). Mirrors the
            // displayRoomName / displayAvatarUrl logic in RoomTimelineScreen so the room header's
            // avatar and the RoomInfo avatar stay in sync — without this, opening RoomInfo for a
            // DM hands AvatarImage a null mxcUrl, which now renders the native Text fallback.
            // See docs/ROOM_DISPLAY.md for the rule.
            val needsHeroesFallback = roomState!!.name.isNullOrBlank() &&
                roomState!!.canonicalAlias.isNullOrBlank()
            val heroMember: RoomMember? = if (needsHeroesFallback) {
                val myUserId = appViewModel.currentUserId
                val serviceMembers = appViewModel.functionalMembersCache[roomId] ?: emptySet()
                roomMembers
                    .asSequence()
                    .filter { it.membership == "join" }
                    .filter { it.userId != myUserId && it.userId !in serviceMembers }
                    .firstOrNull()
                    ?: roomMembers
                        .asSequence()
                        .filter { it.userId != myUserId && it.userId !in serviceMembers }
                        .firstOrNull()
            } else {
                null
            }
            val effectiveAvatarUrl = roomState!!.avatarUrl ?: heroMember?.avatarUrl
            val effectiveName = roomState!!.name
                ?: heroMember?.displayName
                ?: heroMember?.userId?.let { usernameFromMatrixId(it) }

            val roomInfoScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                    // Whole screen scrolls so long topics / aliases cannot push buttons off-screen
                    .verticalScroll(roomInfoScrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Room ID
                Text(
                    text = roomState!!.roomId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Room Display Name and Canonical Alias
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    effectiveName?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Canonical Alias directly below room name
                    roomState!!.canonicalAlias?.let { alias ->
                        Text(
                            text = alias,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                // Room Avatar
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clickable(enabled = effectiveAvatarUrl != null) {
                                val avatarUrl = effectiveAvatarUrl
                                if (!avatarUrl.isNullOrBlank()) {
                                    fullAvatarMxc = avatarUrl
                                    showFullAvatarDialog = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                            val sharedKey = "avatar-$roomId"
                            with(sharedTransitionScope) {
                                AvatarImage(
                                    mxcUrl = effectiveAvatarUrl,
                                    homeserverUrl = appViewModel.homeserverUrl,
                                    authToken = appViewModel.authToken,
                                    fallbackText = effectiveName ?: roomId,
                                    size = 120.dp,
                                    userId = roomId,
                                    displayName = effectiveName,
                                    capAvatarSize = true, // Match RoomListScreen's avatar size so shared-element transitions hit the same Coil cache key
                                    modifier = Modifier
                                        .sharedElement(
                                            rememberSharedContentState(key = sharedKey),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                            boundsTransform = { _, _ ->
                                                // Unified shared-element flight spec (RL↔RT, RT↔RoomInfo,
                                                // RT↔UserInfo): a single non-bouncy tween bound to the
                                                // duration slider.
                                                tween(durationMillis = scaledTweenMs(380), easing = LinearEasing)
                                            },
                                            renderInOverlayDuringTransition = true,
                                            zIndexInOverlay = 1f,
                                        )
                                        .clip(CircleShape),
                                )
                            }
                        } else {
                            AvatarImage(
                                mxcUrl = effectiveAvatarUrl,
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = appViewModel.authToken,
                                fallbackText = effectiveName ?: roomId,
                                size = 120.dp,
                                userId = roomId,
                                displayName = effectiveName,
                                capAvatarSize = true, // Match RoomListScreen's avatar size so shared-element transitions hit the same Coil cache key
                                modifier = Modifier.clip(CircleShape),
                            )
                        }

                        // DM badge: show whenever the room is a DM per m.direct, OR when the
                        // heroes fallback is active (nameless/avatarless room — typically a DM
                        // not yet recorded in m.direct). Bridged DMs (WhatsApp, etc.) have an
                        // explicit m.room.name set by the bridge, so the heroes-only check would
                        // miss them even though m.direct says they're DMs.
                        val selfProfile = appViewModel.currentUserProfile
                        val isDmRoom = heroMember != null ||
                            appViewModel.isDirectMessageFromAccountData(roomId)
                        if (isDmRoom && selfProfile != null) {
                            // Badge alpha is driven by the screen's enter/exit transition state.
                            //  • Entry: delay so the shared-element avatar from RT's header can
                            //    settle (500 ms covers the StiffnessLow spring), then fade in 125 ms.
                            //  • Exit: fade out fast (125 ms) the instant the screen starts leaving.
                            //    Without this the badge — which is hoisted into the shared-transition
                            //    overlay (zIndex 2f) and is NOT itself a shared element — stays frozen
                            //    at full alpha on top while the room avatar flies back to the header.
                            val badgeAlpha = remember { Animatable(0f) }
                            val isExiting = animatedVisibilityScope?.transition?.targetState == EnterExitState.PostExit
                            LaunchedEffect(isExiting) {
                                if (isExiting) {
                                    badgeAlpha.animateTo(0f, animationSpec = tween(durationMillis = scaledTweenMs(125)))
                                } else {
                                    kotlinx.coroutines.delay(500)
                                    badgeAlpha.animateTo(1f, animationSpec = tween(durationMillis = scaledTweenMs(125)))
                                }
                            }
                            // While the shared-element transition runs, the room avatar is hoisted
                            // into the SharedTransitionScope overlay (zIndexInOverlay=1f), which
                            // draws above any sibling in the normal composition. Hoist the badge
                            // into the same overlay at a higher zIndex so it sits on top instead
                            // of being clipped behind the avatar during settle.
                            val overlayModifier = if (sharedTransitionScope != null) {
                                with(sharedTransitionScope) {
                                    Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 2f)
                                }
                            } else {
                                Modifier
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(40.dp)
                                    .then(overlayModifier)
                                    .alpha(badgeAlpha.value)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .padding(2.dp),
                            ) {
                                AvatarImage(
                                    mxcUrl = selfProfile.avatarUrl,
                                    homeserverUrl = appViewModel.homeserverUrl,
                                    authToken = appViewModel.authToken,
                                    fallbackText = selfProfile.displayName ?: selfProfile.userId,
                                    size = 36.dp,
                                    userId = selfProfile.userId,
                                    displayName = selfProfile.displayName,
                                    capAvatarSize = true,
                                    modifier = Modifier.clip(CircleShape),
                                )
                            }
                        }
                    }
                }

                // Room Topic: cap height + nested scroll so button row stays reachable without
                // scrolling through pages of topic text (get_room_state can return huge m.room.topic)
                roomState!!.topic?.let { topic ->
                    val topicScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Topic",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = topic,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                // Max height keeps Power Levels / ACL / Pinned / Members visible below
                                .heightIn(max = 220.dp)
                                .verticalScroll(topicScrollState),
                        )
                    }
                }

                // Power Levels and ACL Buttons side by side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Power Levels Button
                    if (roomState!!.powerLevels != null) {
                        Button(
                            onClick = { showPowerLevelsDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) {
                            Text(
                                text = "Power\nLevels",
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    // Server ACL Button
                    Button(
                        onClick = { showServerAclDialog = true },
                        enabled = roomState!!.serverAcl != null,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Text(
                            text = "ACL List",
                            textAlign = TextAlign.Center,
                        )
                    }

                    // Pinned Events Button
                    Button(
                        onClick = {
                            val pinnedIds = roomState!!.pinnedEventIds
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
                                    },
                                )
                            } else {
                                pinnedEvents = emptyList()
                                pinnedError = null
                                showPinnedDialog = true
                            }
                        },
                        enabled = roomState!!.pinnedEventIds.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                    ) {
                        Text(
                            text = "Pinned",
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // Members + Media Gallery + Push Rules Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            memberDialogSearchQuery = ""
                            showMembersDialog = true
                            // The list already in `roomMembers` is whatever the backend's database
                            // held when the screen opened — for a room it never fetched members
                            // for, that's the lazy-loaded subset. Opening the dialog is the user
                            // explicitly asking "who is in this room", so re-ask with
                            // fetch_members=true and show the cached list until it lands.
                            isMembersRefreshing = true
                            appViewModel.requestRoomStateWithMembers(roomId, fetchMembers = true) { state, members, error ->
                                isMembersRefreshing = false
                                if (error != null) {
                                    android.util.Log.w(
                                        "Andromuks",
                                        "RoomInfoScreen: Member refresh failed, keeping cached list: $error",
                                    )
                                } else {
                                    if (state != null) roomState = state
                                    roomMembers = members
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("Members", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = {
                            navController.navigate("room_media_gallery/$roomId")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(
                            "Media\nGallery",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = { showPushRulesDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("Push\nRules", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                }

                // Room Preferences + room-scoped per-message profiles (MSC4461 rev-3 stores both
                // profiles and a default_profile_id in room account data).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            navController.navigate(
                                "room_preferences/${java.net.URLEncoder.encode(roomId, "UTF-8")}",
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(
                            "Room\nPreferences",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = {
                            navController.navigate(
                                "per_message_profile_editor/${java.net.URLEncoder.encode(roomId, "UTF-8")}",
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(
                            "Per-Message\nProfiles",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Technical (cache)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )

                            Text(
                                text = "Room cached events: $cachedEventCountForRoom",
                            )

                            Text(
                                text = "Room profile count: $roomSpecificProfileCount",
                            )

                            Text(
                                text = "Timeline cache (room) est: ${"%.1f".format(timelineCacheKbForRoom)}KB",
                            )

                            Text(
                                text = "Room profiles cache (est): ${"%.1f".format(roomProfilesKbForRoom)}KB",
                            )
                        }
                    }
                }
            }
        }
    }

    // Leave Room Confirmation Dialog
    if (showLeaveRoomDialog && roomState != null) {
        AlertDialog(
            onDismissRequest = { showLeaveRoomDialog = false },
            title = {
                Text("Leave Room")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Are you sure you want to leave")
                    Text(
                        text = roomState!!.name ?: roomState!!.canonicalAlias ?: "Unknown Room",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = roomId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLeaveRoomDialog = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // Power Levels Dialog
    if (showPowerLevelsDialog && roomState?.powerLevels != null) {
        PowerLevelsDialog(
            powerLevels = roomState!!.powerLevels!!,
            onDismiss = { showPowerLevelsDialog = false },
        )
    }

    // Server ACL Dialog
    if (showServerAclDialog && roomState?.serverAcl != null) {
        ServerAclDialog(
            serverAcl = roomState!!.serverAcl!!,
            onDismiss = { showServerAclDialog = false },
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

                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                    "Andromuks",
                    "RoomInfoScreen: Triggering opportunistic profile loading for ${senders.size} pinned event senders",
                )
                }
                senders.forEach { sender ->
                    if (!requestedPinnedSenders.contains(sender)) {
                        requestedPinnedSenders = requestedPinnedSenders + sender
                        // 1) Room-specific state (best source for room profile).
                        appViewModel.requestUserProfileOnDemand(sender, roomId)
                        // 2) Deterministic global fallback with direct callback.
                        appViewModel.requestBasicUserProfile(sender) { profile ->
                            if (profile != null) {
                                val displayName =
                                    profile.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(
                                        sender,
                                    )
                                pinnedDirectProfiles = pinnedDirectProfiles + (
                                    sender to MemberProfile(
                                        displayName = displayName,
                                        avatarUrl = profile.avatarUrl,
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
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "RoomInfoScreen: Refreshing room state after unpin",
                    )
                }
                appViewModel.requestRoomStateWithMembers(roomId) { state, members, error ->
                    if (error != null) {
                        android.util.Log.e("Andromuks", "RoomInfoScreen: Error refreshing room state: $error")
                        pinnedError = error
                        isPinnedLoading = false
                    } else {
                        roomState = state
                        roomMembers = members
                        val pinnedIds = state?.pinnedEventIds ?: emptyList()
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "RoomInfoScreen: Refreshed room state, found ${pinnedIds.size} pinned events",
                            )
                        }

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
                                },
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
            },
        )
    }

    if (showFullAvatarDialog && fullAvatarMxc != null) {
        ImageViewerDialog(
            mediaMessage = avatarImageMediaMessage(fullAvatarMxc!!),
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            isEncrypted = false,
            onDismiss = { showFullAvatarDialog = false },
        )
    }

    // Per-room Push Rules Dialog
    if (showPushRulesDialog) {
        RoomPushRulesDialog(
            roomId = roomId,
            roomName = roomState?.name ?: roomState?.canonicalAlias ?: roomId,
            appViewModel = appViewModel,
            onDismiss = { showPushRulesDialog = false },
        )
    }

    // Members Dialog
    if (showMembersDialog && roomState != null) {
        MembersDialog(
            members = roomMembers,
            powerLevels = roomState!!.powerLevels,
            creators = RoomPermissions.creatorsOf(roomState),
            isRefreshing = isMembersRefreshing,
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
            },
        )
    }
}

/**
 * Per-room push rules editor. Lets the user pick a notification level for this room (backed by the
 * room-scoped push rule keyed by roomId) and review/toggle any other rules that specifically target
 * the room. Writes go through [AppViewModel]'s push-rule forwarders; the next sync reconciles.
 */
@Composable
private fun RoomPushRulesDialog(roomId: String, roomName: String, appViewModel: AppViewModel, onDismiss: () -> Unit) {
    val ruleset = appViewModel.pushRuleset
    val currentLevel = ruleset.roomNotificationLevel(roomId)
    val allAffecting = ruleset.rulesAffectingRoom(roomId)
    var ruleQuery by remember { mutableStateOf("") }
    val affecting = if (ruleQuery.isBlank()) {
        allAffecting
    } else {
        allAffecting.filter { rule ->
            val q = ruleQuery.trim().lowercase()
            rule.ruleId.lowercase().contains(q) ||
                rule.displayTitle().lowercase().contains(q) ||
                rule.humanSummary().lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Push Rules") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = roomName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Notifications for this room",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val levels = listOf(
                    RoomNotificationLevel.ALL to "All messages",
                    RoomNotificationLevel.DEFAULT to "Default (use account rules)",
                    RoomNotificationLevel.MUTE to "Mute",
                )
                levels.forEach { (level, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (level != currentLevel) {
                                    appViewModel.setRoomNotificationLevel(
                                        roomId,
                                        level,
                                    )
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = level == currentLevel,
                            onClick = {
                                if (level != currentLevel) {
                                    appViewModel.setRoomNotificationLevel(
                                        roomId,
                                        level,
                                    )
                                }
                            },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (allAffecting.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Other rules affecting this room",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ruleQuery,
                        onValueChange = { ruleQuery = it },
                        placeholder = { Text("Search rules") },
                        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (affecting.isEmpty()) {
                            item {
                                Text(
                                    text = "No rules match \"$ruleQuery\".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(affecting) { rule ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${rule.displayTitle()} (${rule.kind.displayName.lowercase()})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = rule.humanSummary(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { appViewModel.setPushRuleEnabled(rule.kind, rule.ruleId, it) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

data class PinnedEventItem(val eventId: String, val timelineEvent: net.vrkknn.andromuks.TimelineEvent?)

private fun loadPinnedEvents(pinnedIds: List<String>, roomId: String, appViewModel: AppViewModel, onResult: (List<PinnedEventItem>, String?) -> Unit) {
    if (pinnedIds.isEmpty()) {
        onResult(emptyList(), null)
        return
    }

    val results = mutableListOf<PinnedEventItem>()
    var remaining = pinnedIds.size
    var errorMessage: String? = null
    var hasTimeout = false

    if (BuildConfig.DEBUG) {
        android.util.Log.d(
            "Andromuks",
            "loadPinnedEvents: Loading ${pinnedIds.size} pinned events for room $roomId",
        )
    }

    pinnedIds.forEach { eventId ->
        appViewModel.getEvent(roomId, eventId) { timelineEvent ->
            synchronized(results) {
                results.add(PinnedEventItem(eventId, timelineEvent))
                remaining -= 1

                if (timelineEvent == null) {
                    hasTimeout = true
                    android.util.Log.w("Andromuks", "loadPinnedEvents: Failed to load event $eventId")
                } else {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "loadPinnedEvents: Successfully loaded event $eventId",
                        )
                    }
                }

                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "loadPinnedEvents: Progress: ${pinnedIds.size - remaining}/${pinnedIds.size} events loaded",
                    )
                }

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
                        hasTimeout && results.all {
                            it.timelineEvent == null
                        } -> "All pinned events failed to load (timeout or not found)"

                        hasTimeout -> "Some pinned events failed to load (timeout or not found)"

                        else -> null
                    }

                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                        "Andromuks",
                        "loadPinnedEvents: Completed loading pinned events. Success: ${results.count { it.timelineEvent != null }}/${pinnedIds.size}",
                    )
                    }
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "loadPinnedEvents: Events ordered by timestamp (most recent first)",
                        )
                    }
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
    onDismiss: () -> Unit,
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
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveLoadingIndicator()
                    }
                }

                errorMessage != null && pinnedEvents.isEmpty() -> {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
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
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                onDismiss = onDismiss,
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
        },
    )
}

@Composable
private fun MembersDialog(
    members: List<RoomMember>,
    powerLevels: PowerLevelsInfo?,
    creators: Set<String>,
    isRefreshing: Boolean,
    memberMap: Map<String, MemberProfile>,
    memberSearchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    homeserverUrl: String,
    authToken: String,
    navController: NavController,
    roomId: String,
    onDismiss: () -> Unit,
) {
    // Sort and filter members
    val sortedAndFilteredMembers = remember(members, powerLevels, creators, memberMap, memberSearchQuery) {
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

        // Drop members who have left — they are no longer in the room.
        // Group the rest: active members, then banned, then knockers at the very bottom.
        val activeMembers = filtered.filter {
            it.membership != "ban" && it.membership != "leave" && it.membership != "knock"
        }
        val banMembers = filtered.filter { it.membership == "ban" }
        val knockMembers = filtered.filter { it.membership == "knock" }

        // Sort active members: by power level (descending), then by room-specific displayname, then global displayname, then username
        val sortedActive = activeMembers.sortedWith(
            compareBy<RoomMember>(
                // Creators outrank everyone: from room version 12 they hold power that
                // m.room.power_levels never states, so reading `users` alone would file them
                // under users_default and scatter them through the middle of the list.
                { -RoomPermissions.powerLevelOf(powerLevels, creators, it.userId) },
                { it.displayName?.lowercase() ?: "" }, // Room-specific displayname
                { memberMap[it.userId]?.displayName?.lowercase() ?: "" }, // Global displayname
                { usernameFromMatrixId(it.userId).lowercase() }, // Username
            ),
        )

        // Sort banned/knocking members alphabetically by room-specific displayname, then global displayname, then username
        val alphabetical = compareBy<RoomMember>(
            { it.displayName?.lowercase() ?: "" }, // Room-specific displayname
            { memberMap[it.userId]?.displayName?.lowercase() ?: "" }, // Global displayname
            { usernameFromMatrixId(it.userId).lowercase() }, // Username
        )
        val sortedBan = banMembers.sortedWith(alphabetical)
        val sortedKnock = knockMembers.sortedWith(alphabetical)

        // Active members first, then banned, then knockers at the bottom
        sortedActive + sortedBan + sortedKnock
    }

    val joinedCount = members.count { it.membership == "join" }
    val invitedCount = members.count { it.membership == "invite" }
    // Members still in the room (everyone except those who have left)
    val presentCount = members.count { it.membership != "leave" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Room Members ($presentCount)")
                    if (isRefreshing) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ExpressiveLoadingIndicator(modifier = Modifier.size(20.dp))
                    }
                }
                if (invitedCount > 0) {
                    Text(
                        text = "$joinedCount joined, $invitedCount invited",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                        )
                    },
                )

                // Member list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (sortedAndFilteredMembers.isEmpty()) {
                        item {
                            Text(
                                text = if (memberSearchQuery.isBlank()) "No members found" else "No members match your search",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        items(sortedAndFilteredMembers) { member ->
                            RoomMemberItem(
                                member = member,
                                homeserverUrl = homeserverUrl,
                                authToken = authToken,
                                powerLevel = powerLevels?.users?.get(member.userId),
                                isCreator = member.userId in creators,
                                onUserClick = { userId ->
                                    navController.navigateToUserInfo(userId, roomId)
                                },
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
        },
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
    onDismiss: () -> Unit,
) {
    val event = pinnedItem.timelineEvent
    var showUnpinDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Debug logging for profile loading
    LaunchedEffect(event?.sender, memberMap) {
        if (event?.sender != null) {
            val profile = memberMap[event.sender]
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "PinnedEventItemView: Event sender: ${event.sender}, Profile found: ${profile != null}, DisplayName: ${profile?.displayName}",
                )
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = event?.eventId ?: pinnedItem.eventId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (event == null) {
                Text(
                    text = "Event data is not available (404)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        },
                    ),
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
                        },
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Unpin")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpinDialog = false }) {
                    Text("Cancel")
                }
            },
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
    powerLevel: Long?,
    isCreator: Boolean = false,
    onUserClick: (String) -> Unit = {},
) {
    val displayName = member.displayName ?: usernameFromMatrixId(member.userId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(member.userId) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            mxcUrl = member.avatarUrl,
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = displayName.take(1),
            size = 40.dp,
            userId = member.userId,
            displayName = displayName,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = member.userId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Show membership status badge if not joined
        if (member.membership != "join") {
            Surface(
                color = when (member.membership) {
                    "invite" -> MaterialTheme.colorScheme.tertiaryContainer
                    "knock" -> MaterialTheme.colorScheme.secondaryContainer
                    "ban" -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = member.membership.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (member.membership) {
                        "invite" -> MaterialTheme.colorScheme.onTertiaryContainer
                        "knock" -> MaterialTheme.colorScheme.onSecondaryContainer
                        "ban" -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // Show power level badge if user has special powers. A creator's power is not in
        // m.room.power_levels at all, so it is rendered from the create event rather than from
        // powerLevel — which for them is usually null.
        if (isCreator || (powerLevel != null && powerLevel > 0)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    // A creator has no level to format; anyone else in this branch has a non-null one.
                    text = "PL: " + if (isCreator || powerLevel == null) "∞" else RoomPermissions.formatPowerLevel(powerLevel),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Dialog to display power levels information
 */
@Composable
fun PowerLevelsDialog(powerLevels: PowerLevelsInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Power Levels") },
        text = {
            LazyColumn {
                item {
                    Text(
                        text = "Default Levels",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Users Default: ${RoomPermissions.formatPowerLevel(powerLevels.usersDefault)}")
                    Text("Events Default: ${RoomPermissions.formatPowerLevel(powerLevels.eventsDefault)}")
                    Text("State Default: ${RoomPermissions.formatPowerLevel(powerLevels.stateDefault)}")
                    Text("Ban: ${RoomPermissions.formatPowerLevel(powerLevels.ban)}")
                    Text("Kick: ${RoomPermissions.formatPowerLevel(powerLevels.kick)}")
                    Text("Redact: ${RoomPermissions.formatPowerLevel(powerLevels.redact)}")
                    Text("Invite: ${RoomPermissions.formatPowerLevel(powerLevels.invite)}")

                    if (powerLevels.users.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "User Power Levels",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                items(powerLevels.users.entries.toList()) { (userId, level) ->
                    Text("$userId: ${RoomPermissions.formatPowerLevel(level)}")
                }

                item {
                    if (powerLevels.events.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Event Power Levels",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                items(powerLevels.events.entries.toList()) { (eventType, level) ->
                    Text("$eventType: ${RoomPermissions.formatPowerLevel(level)}")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

/**
 * Dialog to display server ACL information
 */
@Composable
fun ServerAclDialog(serverAcl: ServerAclInfo, onDismiss: () -> Unit) {
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
                        fontWeight = FontWeight.Bold,
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
                        fontWeight = FontWeight.Bold,
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
        },
    )
}
