package net.vrkknn.andromuks

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.utils.navigateToUserInfo
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.LocalActiveMessageMenuEventId
import net.vrkknn.andromuks.utils.MessageMenuBar
import net.vrkknn.andromuks.utils.MessageMenuConfig
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.LocalScrollHighlightState
import net.vrkknn.andromuks.ScrollHighlightState
import net.vrkknn.andromuks.ui.components.LocalIsScrollingFast
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import coil.request.ImageRequest
import coil.request.CachePolicy
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventContextScreen(
    roomId: String,
    eventId: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPreferences = remember(context) {
        context.getSharedPreferences("AndromuksAppPrefs", android.content.Context.MODE_PRIVATE)
    }
    val authToken = remember(sharedPreferences) {
        sharedPreferences.getString("gomuks_auth_token", "") ?: ""
    }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    val myUserId = appViewModel.currentUserId
    val homeserverUrl = appViewModel.homeserverUrl
    
    // State for event context
    var contextEvents by remember(roomId, eventId) { mutableStateOf<List<TimelineEvent>?>(null) }
    var isLoading by remember(roomId, eventId) { mutableStateOf(true) }
    var errorMessage by remember(roomId, eventId) { mutableStateOf<String?>(null) }
    
    // Get room name for header
    val roomItem = appViewModel.getRoomById(roomId)
    val roomDisplayName = roomItem?.name ?: roomId
    val headerTitle = roomDisplayName

    // Message menu state
    var messageMenuConfig by remember { mutableStateOf<MessageMenuConfig?>(null) }
    var retainedMessageMenuConfig by remember { mutableStateOf<MessageMenuConfig?>(null) }
    var showReactionsDialog by remember { mutableStateOf(false) }
    var reactionsEventId by remember { mutableStateOf<String?>(null) }
    var showBridgeDeliveryDialog by remember { mutableStateOf(false) }
    var bridgeDeliveryEventId by remember { mutableStateOf<String?>(null) }
    var showCodeViewer by remember { mutableStateOf(false) }
    var codeViewerContent by remember { mutableStateOf("") }

    LaunchedEffect(messageMenuConfig) {
        if (messageMenuConfig != null) {
            retainedMessageMenuConfig = messageMenuConfig
        }
    }

    BackHandler(enabled = messageMenuConfig != null) {
        messageMenuConfig = null
    }

    // Fetch event context when screen is created
    LaunchedEffect(roomId, eventId) {
        if (BuildConfig.DEBUG) Log.d("Andromuks", "EventContextScreen: Fetching event context for roomId: $roomId, eventId: $eventId")
        isLoading = true
        errorMessage = null
        contextEvents = null
        
        appViewModel.getEventContext(roomId, eventId, limitBefore = 5, limitAfter = 5) { events ->
            isLoading = false
            if (events != null) {
                // Sort by timeline_rowid (server order), not timestamp - timestamps can be out of order
                contextEvents = events.sortedWith(compareBy({ it.timelineRowid }, { it.timestamp }, { it.eventId }))
                if (BuildConfig.DEBUG) Log.d("Andromuks", "EventContextScreen: Received ${events.size} events in context")
            } else {
                errorMessage = "Failed to load event context"
                if (BuildConfig.DEBUG) Log.w("Andromuks", "EventContextScreen: Failed to load event context")
            }
        }
    }
    
    // Get member map with fallback to ensure profiles are loaded for all users in events
    val baseMemberMap = remember(roomId, appViewModel.memberUpdateCounter, contextEvents) {
        if (contextEvents != null) {
            appViewModel.getMemberMapWithFallback(roomId, contextEvents)
        } else {
            appViewModel.getMemberMap(roomId)
        }
    }
    
    // CRITICAL FIX: Ensure current user profile is included in memberMap
    val memberMap = remember(baseMemberMap, appViewModel.currentUserProfile, myUserId) {
        val enhancedMap = baseMemberMap.toMutableMap()
        
        // If current user is not in member map but we have currentUserProfile, add it
        if (myUserId != null && myUserId.isNotBlank() && !enhancedMap.containsKey(myUserId)) {
            val currentProfile = appViewModel.currentUserProfile
            if (currentProfile != null) {
                enhancedMap[myUserId] = MemberProfile(
                    displayName = currentProfile.displayName,
                    avatarUrl = currentProfile.avatarUrl
                )
            }
        }
        
        enhancedMap
    }
    
    // Load profiles for users in the context events
    LaunchedEffect(contextEvents, roomId) {
        val events = contextEvents
        if (events != null && events.isNotEmpty()) {
            // Request profiles for all unique users in the context events
            val uniqueUsers = events.map { it.sender }.distinct()
                .filter { it != appViewModel.currentUserId && it.isNotBlank() }
            
            if (BuildConfig.DEBUG) Log.d("Andromuks", "EventContextScreen: Requesting profiles for ${uniqueUsers.size} users in context events")
            
            uniqueUsers.forEach { userId ->
                appViewModel.requestUserProfileOnDemand(userId, roomId)
            }
        }
    }
    
    // Find the target event in the context events
    val targetEvent = contextEvents?.find { it.eventId == eventId }
    val editEventsByTargetId: Map<String, TimelineEvent> = remember(contextEvents) {
        val events = contextEvents ?: return@remember emptyMap()
        val map = mutableMapOf<String, TimelineEvent>()
        for (event in events) {
            val targetId =
                event.content?.optJSONObject("m.relates_to")
                    ?.takeIf { it.optString("rel_type") == "m.replace" }
                    ?.optString("event_id")?.takeIf { it.isNotBlank() }
                    ?: event.decrypted?.optJSONObject("m.relates_to")
                        ?.takeIf { it.optString("rel_type") == "m.replace" }
                        ?.optString("event_id")?.takeIf { it.isNotBlank() }
            if (targetId != null) {
                val existing = map[targetId]
                if (existing == null || event.timestamp > existing.timestamp) {
                    map[targetId] = event
                }
            }
        }
        map
    }
    
    CompositionLocalProvider(
        LocalActiveMessageMenuEventId provides messageMenuConfig?.event?.eventId,
        LocalScrollHighlightState provides ScrollHighlightState(),
        LocalIsScrollingFast provides false
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(headerTitle) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    ExpressiveLoadingIndicator()
                }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage ?: "Error loading event context",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            contextEvents == null || contextEvents!!.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No events found in context")
                }
            }
            else -> {
                val listState = rememberLazyListState()
                val coroutineScope = rememberCoroutineScope()
                val timelinePrefetchLoader = remember(context) { ImageLoaderSingleton.get(context) }
                val prefetchedTimelineMemoryKeys = remember { mutableSetOf<String>() }
                
                // Prefetch avatars: uses avatar-style URL (with avatar thumbnail params)
                fun enqueueAvatarPrefetch(mxcUrl: String?) {
                    if (mxcUrl.isNullOrBlank()) return
                    val httpUrl = AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl) ?: return
                    if (!prefetchedTimelineMemoryKeys.add(httpUrl)) return
                    val request = ImageRequest.Builder(context)
                        .data(httpUrl)
                        .addHeader("Cookie", "gomuks_auth=$authToken")
                        .size(256)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    timelinePrefetchLoader.enqueue(request)
                }

                // Prefetch media thumbnails: uses the same URL format as MediaContent's AsyncImage
                // (MediaUtils.mxcToThumbnailUrl → ?thumbnail=600,600, no custom memoryCacheKey)
                // so the prefetched image is found in Coil's cache when the item renders.
                fun enqueueMediaPrefetch(mxcUrl: String?) {
                    if (mxcUrl.isNullOrBlank()) return
                    val httpUrl = net.vrkknn.andromuks.utils.MediaUtils.mxcToThumbnailUrl(mxcUrl, homeserverUrl) ?: return
                    if (!prefetchedTimelineMemoryKeys.add(httpUrl)) return
                    val request = ImageRequest.Builder(context)
                        .data(httpUrl)
                        .addHeader("Cookie", "gomuks_auth=$authToken")
                        .size(600, 600)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    timelinePrefetchLoader.enqueue(request)
                }
                
                // Prefetch media for visible events
                LaunchedEffect(listState, contextEvents, memberMap, homeserverUrl, authToken, roomId) {
                    val events = contextEvents ?: return@LaunchedEffect
                    snapshotFlow {
                        val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
                        if (visibleIndices.isEmpty()) {
                            null
                        } else {
                            (visibleIndices.minOrNull() ?: 0) to (visibleIndices.maxOrNull() ?: 0)
                        }
                    }
                        .filterNotNull()
                        .distinctUntilChanged()
                        .collect { (visibleStart, visibleEnd) ->
                            if (events.isEmpty()) return@collect
                            val start = (visibleStart - 2).coerceAtLeast(0)
                            val end = (visibleEnd + 2).coerceAtMost(events.lastIndex)
                            
                            for (index in start..end) {
                                val event = events.getOrNull(index) ?: continue
                                
                                // Prefetch sender avatar
                                enqueueAvatarPrefetch(memberMap[event.sender]?.avatarUrl)

                                // Prefetch media thumbnail for image/video/sticker events
                                val content = when {
                                    event.type == "m.room.message" -> event.content
                                    event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted
                                    event.type == "m.sticker" -> event.content ?: event.decrypted
                                    else -> null
                                }
                                val msgType = when {
                                    event.type == "m.sticker" -> "m.sticker"
                                    else -> content?.optString("msgtype", "")
                                }
                                if (msgType == "m.image" || msgType == "m.video" || msgType == "m.sticker") {
                                    val info = content?.optJSONObject("info")
                                    val thumbnailMxc =
                                        info?.optJSONObject("thumbnail_file")
                                            ?.optString("url")
                                            ?.takeIf { it.isNotBlank() }
                                            ?: info?.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
                                    val mediaMxc = content?.optString("url", "")?.takeIf { it.isNotBlank() }
                                    enqueueMediaPrefetch(thumbnailMxc ?: mediaMxc)
                                }
                            }
                        }
                }
                
                // Scroll to target event when events are loaded
                LaunchedEffect(contextEvents) {
                    if (contextEvents != null && targetEvent != null) {
                        val targetIndex = contextEvents!!.indexOf(targetEvent)
                        if (targetIndex >= 0) {
                            kotlinx.coroutines.delay(100) // Small delay for layout
                            listState.animateScrollToItem(targetIndex)
                        }
                    }
                }
                
                // clipToBounds ensures the date pill slides from behind the header rather than over it
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .clipToBounds()
                ) {
                    // Standard layout (oldest first, no reverseLayout) — first visible index is oldest
                    val oldestVisibleDateContext by remember(contextEvents) {
                        derivedStateOf {
                            val idx = listState.firstVisibleItemIndex
                            contextEvents?.getOrNull(idx)?.let { event ->
                                formatDate(event.timestamp)
                            }
                        }
                    }
                    val scrollKeyContext by remember { derivedStateOf { listState.firstVisibleItemIndex } }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(contextEvents!!, key = { it.eventId }) { event ->
                            val isTargetEvent = event.eventId == eventId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isTargetEvent) {
                                            Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                TimelineEventItem(
                                    event = event,
                                    timelineEvents = contextEvents!!,
                                    editsByTargetId = editEventsByTargetId,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    userProfileCache = memberMap,
                                    isMine = myUserId != null && event.sender == myUserId,
                                    myUserId = myUserId,
                                    appViewModel = appViewModel,
                                    onScrollToMessage = { replyEventId ->
                                        val index = contextEvents!!.indexOfFirst { it.eventId == replyEventId }
                                        if (index >= 0) {
                                            coroutineScope.launch {
                                                listState.animateScrollToItem(index)
                                            }
                                        } else {
                                            val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                            val encodedEventId = java.net.URLEncoder.encode(replyEventId, "UTF-8")
                                            navController.navigate("event_context/$encodedRoomId/$encodedEventId")
                                        }
                                    },
                                    onUserClick = { userId ->
                                        navController.navigateToUserInfo(userId, roomId)
                                    },
                                    onShowMenu = { menuConfig ->
                                        messageMenuConfig = menuConfig.copy(
                                            onShowReactions = {
                                                reactionsEventId = menuConfig.event.eventId
                                                showReactionsDialog = true
                                            },
                                            onShowBridgeDeliveryInfo = if (appViewModel.messageBridgeSendStatus.containsKey(menuConfig.event.eventId)) {
                                                {
                                                    bridgeDeliveryEventId = menuConfig.event.eventId
                                                    showBridgeDeliveryDialog = true
                                                }
                                            } else null
                                        )
                                    },
                                    onShowReactions = {
                                        reactionsEventId = event.eventId
                                        showReactionsDialog = true
                                    },
                                    onCodeBlockClick = { code ->
                                        codeViewerContent = code
                                        showCodeViewer = true
                                    }
                                )
                            }
                        }
                    }

                    // Sticky date pill — shows date of oldest visible event while scrolling up
                    net.vrkknn.andromuks.utils.StickyDateIndicator(
                        oldestVisibleDate = oldestVisibleDateContext,
                        scrollPositionKey = scrollKeyContext,
                        reverseScrollLayout = false,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .zIndex(1f)
                    )

                    // Reactions dialog
                    if (showReactionsDialog && reactionsEventId != null) {
                        val reactions = reactionsEventId?.let { appViewModel.messageReactions[it] } ?: emptyList()
                        net.vrkknn.andromuks.utils.ReactionDetailsDialog(
                            reactions = reactions,
                            homeserverUrl = homeserverUrl,
                            authToken = imageToken,
                            onDismiss = { showReactionsDialog = false },
                            appViewModel = appViewModel,
                            roomId = roomId
                        )
                    }

                    // Bridge delivery dialog
                    if (showBridgeDeliveryDialog && bridgeDeliveryEventId != null) {
                        val evId = bridgeDeliveryEventId!!
                        val deliveryInfo = appViewModel.messageBridgeDeliveryInfo[evId] ?: net.vrkknn.andromuks.BridgeDeliveryInfo()
                        val deliveryStatus = appViewModel.messageBridgeSendStatus[evId] ?: "sent"
                        val networkName = appViewModel.currentRoomState?.bridgeInfo?.displayName
                        net.vrkknn.andromuks.utils.BridgeDeliveryInfoDialog(
                            deliveryInfo = deliveryInfo,
                            status = deliveryStatus,
                            networkName = networkName,
                            homeserverUrl = homeserverUrl,
                            authToken = imageToken,
                            onDismiss = { showBridgeDeliveryDialog = false },
                            appViewModel = appViewModel,
                            roomId = roomId
                        )
                    }

                    // Code viewer
                    if (showCodeViewer) {
                        net.vrkknn.andromuks.utils.CodeViewer(
                            code = codeViewerContent,
                            onDismiss = { showCodeViewer = false }
                        )
                    }

                    // Message menu bar overlay
                    AnimatedVisibility(
                        visible = messageMenuConfig != null,
                        enter = fadeIn(initialAlpha = 1f, animationSpec = tween(durationMillis = 120)),
                        exit = fadeOut(targetAlpha = 1f, animationSpec = tween(durationMillis = 120))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .zIndex(5f),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            MessageMenuBar(
                                menuConfig = messageMenuConfig ?: retainedMessageMenuConfig,
                                onDismiss = { messageMenuConfig = null },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
    } // CompositionLocalProvider
}

