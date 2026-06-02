package net.vrkknn.andromuks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.flow.distinctUntilChanged
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.theme.AndromuksTheme

/** Sealed class for search result items (events and date dividers). */
sealed class SearchResultItem {
    abstract val stableKey: String

    data class Event(val event: TimelineEvent) : SearchResultItem() {
        override val stableKey: String get() = event.eventId
    }

    // anchorEventId disambiguates dividers: search results may be relevance-sorted, so the same
    // calendar date can appear in multiple non-contiguous segments. Keying on the date alone would
    // produce duplicate LazyColumn keys and crash. The anchor is the first event under the divider.
    data class DateDivider(val date: String, val anchorEventId: String) : SearchResultItem() {
        override val stableKey: String get() = "date_${date}_$anchorEventId"
    }
}

/**
 * Message search screen. Lets the user search messages either on the homeserver
 * (`search_server`) or in the local database (`search_local`), with a few options.
 *
 * - **Current room only** (default on): restrict the search to [roomId].
 * - **Sort by time** (default off): order results by timestamp instead of relevance.
 * - **Search local database** (default off, on for E2EE rooms): use `search_local` instead of
 *   `search_server`. E2EE rooms default to local because the homeserver can't search their
 *   encrypted contents.
 * - **Include redacted events** (default off, hidden unless local search is on): only meaningful
 *   for `search_local`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel(),
    roomId: String? = null
) {
    val homeserverUrl = appViewModel.homeserverUrl
    val authToken = appViewModel.authToken
    val myUserId = appViewModel.currentUserId

    val isRoomEncrypted = remember(roomId) {
        when {
            roomId == null -> false
            appViewModel.currentRoomId == roomId && appViewModel.currentRoomState != null ->
                appViewModel.currentRoomState?.isEncrypted ?: false
            else -> isRoomEncryptedFromState(appViewModel.getRoomState(roomId)) ?: false
        }
    }

    var searchTerm by remember { mutableStateOf("") }
    var currentRoomOnly by remember { mutableStateOf(roomId != null) }
    var sortByTime by remember { mutableStateOf(false) }
    // E2EE rooms can't be searched on the server, so default to local search there.
    var searchLocal by remember { mutableStateOf(isRoomEncrypted) }
    var includeRedacted by remember { mutableStateOf(false) }

    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    var nextBatch by remember { mutableStateOf("") }

    fun runSearch(append: Boolean) {
        val term = searchTerm.trim()
        if (term.isEmpty()) return
        isSearching = true
        hasSearched = true
        val roomIds = if (currentRoomOnly && roomId != null) listOf(roomId) else emptyList()
        appViewModel.searchMessages(
            searchTerm = term,
            searchLocal = searchLocal,
            roomIds = roomIds,
            sortByTime = sortByTime,
            includeRedacted = searchLocal && includeRedacted,
            limit = 50,
            nextBatch = if (append) nextBatch else ""
        ) { events, batch ->
            results = if (append) results + events else events
            nextBatch = batch
            isSearching = false
        }
    }

    // Build list items with date dividers (results are ordered as the server returns them).
    val resultItems = remember(results) {
        val items = mutableListOf<SearchResultItem>()
        var lastDate: String? = null
        val seenEventIds = HashSet<String>()
        for (event in results) {
            // Paginated pages can overlap; skip events already shown so LazyColumn keys stay unique.
            if (!seenEventIds.add(event.eventId)) continue
            val date = formatDate(event.timestamp)
            if (lastDate == null || date != lastDate) {
                items.add(SearchResultItem.DateDivider(date, event.eventId))
                lastDate = date
            }
            items.add(SearchResultItem.Event(event))
        }
        items
    }

    val listState = rememberLazyListState()
    val statusBarTop = androidx.compose.foundation.layout.WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()

    // Hoisted out of the LazyColumn content lambda so the footer's presence reacts reliably.
    val hasMore = nextBatch.isNotBlank()

    // Infinite scroll: auto-fetch the next page when the user scrolls near the end.
    LaunchedEffect(listState, hasMore, resultItems.size) {
        if (!hasMore) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (total > 0 && lastVisible >= total - 3 && !isSearching && nextBatch.isNotBlank()) {
                    runSearch(append = true)
                }
            }
    }

    AndromuksTheme {
        Surface {
            Box(modifier = modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = statusBarTop),
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                                Text(
                                    text = "Search",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Search text box
                            OutlinedTextField(
                                value = searchTerm,
                                onValueChange = { searchTerm = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                placeholder = { Text("Search messages") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { runSearch(append = false) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Search,
                                            contentDescription = "Search"
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { runSearch(append = false) })
                            )

                            // Options
                            SearchOptionRow(
                                label = "Current room only",
                                checked = currentRoomOnly,
                                enabled = roomId != null,
                                onCheckedChange = { currentRoomOnly = it }
                            )
                            SearchOptionRow(
                                label = "Sort by time",
                                checked = sortByTime,
                                onCheckedChange = { sortByTime = it }
                            )
                            SearchOptionRow(
                                label = "Search backend database",
                                checked = searchLocal,
                                onCheckedChange = { searchLocal = it }
                            )
                            if (searchLocal) {
                                SearchOptionRow(
                                    label = "Include redacted events",
                                    checked = includeRedacted,
                                    onCheckedChange = { includeRedacted = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    // Results
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            isSearching && results.isEmpty() -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        ExpressiveLoadingIndicator(modifier = Modifier.size(96.dp))
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "Searching...",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            hasSearched && resultItems.isEmpty() -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No results found",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            !hasSearched -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Enter a search term",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            else -> {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .navigationBarsPadding()
                                        .imePadding(),
                                    contentPadding = PaddingValues(
                                        start = 8.dp,
                                        end = 8.dp,
                                        top = 8.dp,
                                        bottom = 8.dp
                                    )
                                ) {
                                    items(
                                        items = resultItems,
                                        key = { it.stableKey }
                                    ) { item ->
                                        when (item) {
                                            is SearchResultItem.DateDivider -> DateDivider(item.date)
                                            is SearchResultItem.Event -> SearchResultCard(
                                                event = item.event,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                myUserId = myUserId,
                                                appViewModel = appViewModel,
                                                navController = navController
                                            )
                                        }
                                    }
                                    // Footer: shown whenever more pages exist. Pages auto-load on
                                    // scroll, but tapping forces the next page too.
                                    if (hasMore) {
                                        item(key = "load_more") {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable(enabled = !isSearching) { runSearch(append = true) }
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSearching) {
                                                    ExpressiveLoadingIndicator(modifier = Modifier.size(32.dp))
                                                } else {
                                                    Text(
                                                        text = "Load more",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.primary
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
            }
        }
    }
}

@Composable
private fun SearchOptionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/** Renders a single search result with room context, sender, timestamp and content. */
@Composable
private fun SearchResultCard(
    event: TimelineEvent,
    homeserverUrl: String,
    authToken: String,
    myUserId: String?,
    appViewModel: AppViewModel,
    navController: NavController
) {
    val roomId = event.roomId
    val room = appViewModel.getRoomById(roomId)
    val roomName = room?.name ?: roomId

    // Request the sender's profile on demand if missing.
    LaunchedEffect(event.sender, roomId) {
        val existing = appViewModel.getUserProfile(event.sender, roomId)
        if (existing == null || existing.displayName.isNullOrBlank()) {
            appViewModel.requestUserProfileOnDemand(event.sender, roomId)
        }
    }

    val senderProfile = appViewModel.getUserProfile(event.sender, roomId)
    val senderName = senderProfile?.displayName ?: event.sender.removePrefix("@").substringBefore(":")
    val senderAvatarUrl = senderProfile?.avatarUrl

    val content = event.decrypted ?: event.content
    val format = content?.optString("format", "")
    val body = if (format == "org.matrix.custom.html") {
        content?.optString("formatted_body", "")?.takeIf { it.isNotBlank() }
            ?: content?.optString("body", "") ?: ""
    } else {
        content?.optString("body", "") ?: ""
    }

    val userProfileCache = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable {
                val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                val encodedEventId = java.net.URLEncoder.encode(event.eventId, "UTF-8")
                navController.navigate("event_context/$encodedRoomId/$encodedEventId")
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            // Room context
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarImage(
                    mxcUrl = room?.avatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = roomName.take(1),
                    size = 24.dp,
                    userId = roomId,
                    displayName = roomName
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = roomName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sender and message
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                AvatarImage(
                    mxcUrl = senderAvatarUrl,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    fallbackText = senderName.take(1),
                    size = 32.dp,
                    userId = event.sender,
                    displayName = senderName
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatTimeShort(event.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    AdaptiveMessageText(
                        event = event,
                        body = body,
                        format = format,
                        userProfileCache = userProfileCache,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        appViewModel = appViewModel,
                        roomId = roomId,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatTimeShort(timestamp: Long): String {
    val formatter = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(timestamp))
}
