package net.vrkknn.andromuks

import android.util.Log
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
import androidx.navigation.NavController
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.TimelineEventItem
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.utils.navigateToUserInfo
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.MediaPreviewDialog
import net.vrkknn.andromuks.BuildConfig
import android.net.Uri
import coil.request.ImageRequest
import coil.request.CachePolicy
import androidx.compose.runtime.snapshotFlow
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
    val headerTitle = "$roomDisplayName pinned event"
    
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
                val timelinePrefetchLoader = remember(context) { ImageLoaderSingleton.get(context) }
                val prefetchedTimelineMemoryKeys = remember { mutableSetOf<String>() }
                
                // Prefetch media for images and videos
                fun enqueueTimelinePrefetch(
                    mxcUrl: String?,
                    keyPrefix: String,
                    requestSize: Int
                ) {
                    if (mxcUrl.isNullOrBlank()) return
                    val httpUrl = AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl) ?: return
                    val memoryKey = "timeline_prefetch:$roomId:$keyPrefix:${mxcUrl.hashCode()}"
                    if (!prefetchedTimelineMemoryKeys.add(memoryKey)) return
                    
                    val request = ImageRequest.Builder(context)
                        .data(httpUrl)
                        .addHeader("Cookie", "gomuks_auth=$authToken")
                        .size(requestSize)
                        .memoryCacheKey(memoryKey)
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
                                val avatarMxc = memberMap[event.sender]?.avatarUrl
                                enqueueTimelinePrefetch(
                                    mxcUrl = avatarMxc,
                                    keyPrefix = "avatar:${event.sender}",
                                    requestSize = 256
                                )
                                
                                // Prefetch media thumbnail (or media URL fallback) for image/video/sticker events
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
                                    enqueueTimelinePrefetch(
                                        mxcUrl = thumbnailMxc ?: mediaMxc,
                                        keyPrefix = "media:${event.eventId}",
                                        requestSize = 512
                                    )
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
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(contextEvents!!) { event ->
                        val isTargetEvent = event.eventId == eventId
                        Surface(
                            color = if (isTargetEvent) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TimelineEventItem(
                                event = event,
                                timelineEvents = contextEvents!!,
                                homeserverUrl = homeserverUrl,
                                authToken = imageToken,
                                userProfileCache = memberMap,
                                isMine = myUserId != null && event.sender == myUserId,
                                myUserId = myUserId,
                                appViewModel = appViewModel,
                                onUserClick = { userId ->
                                    navController.navigateToUserInfo(userId, roomId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

