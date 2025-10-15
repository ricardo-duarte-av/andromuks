package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.MediaCache
import net.vrkknn.andromuks.utils.MediaUtils
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Data class representing a room link
 */
data class RoomLink(
    val roomIdOrAlias: String,
    val viaServers: List<String> = emptyList(),
    val displayText: String = roomIdOrAlias
)

/**
 * Data class for room summary information
 */
data class RoomSummary(
    val roomId: String,
    val avatarUrl: String?,
    val canonicalAlias: String?,
    val guestCanJoin: Boolean,
    val joinRule: String,
    val name: String?,
    val numJoinedMembers: Int,
    val roomType: String?,
    val worldReadable: Boolean,
    val membership: String?,
    val roomVersion: String?
)

/**
 * Extract room link information from various Matrix room URI/URL formats
 */
fun extractRoomLink(href: String): RoomLink? {
    val trimmed = href.trim()
    
    // 1. matrix.to URL: https://matrix.to/#/!roomid:server or #roomalias:server
    if (trimmed.startsWith("https://matrix.to/#/")) {
        val encoded = trimmed.removePrefix("https://matrix.to/#/")
        val parts = encoded.split("?")
        val roomPart = runCatching { URLDecoder.decode(parts[0], Charsets.UTF_8.name()) }.getOrDefault(parts[0])
        
        // Extract via servers from query params
        val viaServers = mutableListOf<String>()
        if (parts.size > 1) {
            val params = parts[1].split("&")
            params.forEach { param ->
                if (param.startsWith("via=")) {
                    viaServers.add(URLDecoder.decode(param.removePrefix("via="), Charsets.UTF_8.name()))
                }
            }
        }
        
        if (roomPart.startsWith("!") || roomPart.startsWith("#")) {
            return RoomLink(roomPart, viaServers, roomPart)
        }
    }
    
    // 2. matrix: URI: matrix:roomid/roomid:server or matrix:r/roomalias:server
    if (trimmed.startsWith("matrix:")) {
        // Match matrix:roomid/... or matrix:r/...
        val roomIdRegex = Regex("matrix:(?:/+)?(?:roomid|r)/([^?/]+)")
        val match = roomIdRegex.find(trimmed)
        if (match != null) {
            val roomPart = match.groupValues[1]
            val decoded = runCatching { URLDecoder.decode(roomPart, Charsets.UTF_8.name()) }.getOrDefault(roomPart)
            
            // Determine if this is a room ID or alias and add the prefix if missing
            val fullIdentifier = when {
                trimmed.contains("matrix:roomid/") || trimmed.contains("matrix:/roomid/") -> {
                    // Room ID - add ! if not present
                    if (decoded.startsWith("!")) decoded else "!$decoded"
                }
                trimmed.contains("matrix:r/") || trimmed.contains("matrix:/r/") -> {
                    // Room alias - add # if not present
                    if (decoded.startsWith("#")) decoded else "#$decoded"
                }
                else -> decoded
            }
            
            // Extract via servers from query params
            val viaServers = mutableListOf<String>()
            if (trimmed.contains("?via=")) {
                val queryPart = trimmed.substringAfter("?")
                queryPart.split("&").forEach { param ->
                    if (param.startsWith("via=")) {
                        viaServers.add(URLDecoder.decode(param.removePrefix("via="), Charsets.UTF_8.name()))
                    }
                }
            }
            
            return RoomLink(fullIdentifier, viaServers, fullIdentifier)
        }
    }
    
    // 3. Direct room alias: #roomalias:server.com
    if (trimmed.startsWith("#") && trimmed.contains(":")) {
        return RoomLink(trimmed, emptyList(), trimmed)
    }
    
    // 4. Direct room ID: !roomid:server.com
    if (trimmed.startsWith("!") && trimmed.contains(":")) {
        return RoomLink(trimmed, emptyList(), trimmed)
    }
    
    return null
}

/**
 * WebSocket helper for room operations
 */
class RoomJoinerWebSocket(
    private val sendMessage: (String) -> Unit,
    private val requestIdCounter: AtomicInteger
) {
    private val pendingRequests = mutableMapOf<Int, (JSONObject) -> Unit>()
    
    /**
     * Handle WebSocket response (both success and error)
     */
    fun handleResponse(response: JSONObject) {
        val requestId = response.optInt("request_id", -1)
        if (requestId != -1) {
            pendingRequests.remove(requestId)?.invoke(response)
        }
    }
    
    /**
     * Check if response is an error
     */
    private fun isErrorResponse(response: JSONObject): Boolean {
        return response.optString("command") == "error"
    }
    
    /**
     * Extract error message from response
     */
    private fun getErrorMessage(response: JSONObject): String? {
        return if (isErrorResponse(response)) {
            response.optString("data")
        } else {
            null
        }
    }
    
    /**
     * Resolve room alias to room ID
     * Returns Pair<roomId, servers> on success, null on error
     */
    suspend fun resolveAlias(alias: String): Pair<String, List<String>>? = withContext(Dispatchers.IO) {
        val requestId = requestIdCounter.incrementAndGet()
        var result: Pair<String, List<String>>? = null
        
        val request = JSONObject().apply {
            put("command", "resolve_alias")
            put("request_id", requestId)
            put("data", JSONObject().apply {
                put("alias", alias)
            })
        }
        
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            pendingRequests[requestId] = { response ->
                // Check if this is an error response
                if (!isErrorResponse(response)) {
                    val data = response.optJSONObject("data")
                    if (data != null) {
                        val roomId = data.optString("room_id")
                        val serversArray = data.optJSONArray("servers")
                        val servers = mutableListOf<String>()
                        if (serversArray != null) {
                            for (i in 0 until serversArray.length()) {
                                servers.add(serversArray.getString(i))
                            }
                        }
                        if (roomId.isNotEmpty()) {
                            result = Pair(roomId, servers)
                        }
                    }
                }
                // On error, result remains null
                continuation.resumeWith(Result.success(Unit))
            }
            
            sendMessage(request.toString())
            
            // Timeout after 10 seconds
            kotlinx.coroutines.MainScope().launch {
                kotlinx.coroutines.delay(10000)
                if (pendingRequests.containsKey(requestId)) {
                    pendingRequests.remove(requestId)
                    continuation.resumeWith(Result.success(Unit))
                }
            }
        }
        
        result
    }
    
    /**
     * Get room summary
     * Returns Pair<RoomSummary?, errorMessage?>
     */
    suspend fun getRoomSummary(roomIdOrAlias: String, viaServers: List<String>): Pair<RoomSummary?, String?> = withContext(Dispatchers.IO) {
        val requestId = requestIdCounter.incrementAndGet()
        var result: RoomSummary? = null
        var errorMessage: String? = null
        
        val request = JSONObject().apply {
            put("command", "get_room_summary")
            put("request_id", requestId)
            put("data", JSONObject().apply {
                put("room_id_or_alias", roomIdOrAlias)
                put("via", JSONArray(viaServers))
            })
        }
        
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            pendingRequests[requestId] = { response ->
                // Check if this is an error response
                if (isErrorResponse(response)) {
                    errorMessage = response.optString("data")
                } else {
                    val data = response.optJSONObject("data")
                    if (data != null) {
                        result = RoomSummary(
                            roomId = data.optString("room_id", ""),
                            avatarUrl = data.optString("avatar_url").takeIf { it.isNotEmpty() },
                            canonicalAlias = data.optString("canonical_alias").takeIf { it.isNotEmpty() },
                            guestCanJoin = data.optBoolean("guest_can_join", false),
                            joinRule = data.optString("join_rule", ""),
                            name = data.optString("name").takeIf { it.isNotEmpty() },
                            numJoinedMembers = data.optInt("num_joined_members", 0),
                            roomType = data.optString("room_type").takeIf { it.isNotEmpty() },
                            worldReadable = data.optBoolean("world_readable", false),
                            membership = data.optString("membership").takeIf { it.isNotEmpty() },
                            roomVersion = data.optString("im.nheko.summary.version").takeIf { it.isNotEmpty() }
                        )
                    }
                }
                continuation.resumeWith(Result.success(Unit))
            }
            
            sendMessage(request.toString())
            
            // Timeout after 10 seconds
            kotlinx.coroutines.MainScope().launch {
                kotlinx.coroutines.delay(10000)
                if (pendingRequests.containsKey(requestId)) {
                    pendingRequests.remove(requestId)
                    continuation.resumeWith(Result.success(Unit))
                }
            }
        }
        
        Pair(result, errorMessage)
    }
    
    /**
     * Join a room
     * Returns Pair<roomId?, errorMessage?>
     */
    suspend fun joinRoom(roomIdOrAlias: String, viaServers: List<String>): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val requestId = requestIdCounter.incrementAndGet()
        var joinedRoomId: String? = null
        var errorMessage: String? = null
        
        val request = JSONObject().apply {
            put("command", "join_room")
            put("request_id", requestId)
            put("data", JSONObject().apply {
                put("room_id_or_alias", roomIdOrAlias)
                if (viaServers.isNotEmpty()) {
                    put("via", JSONArray(viaServers))
                }
            })
        }
        
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            pendingRequests[requestId] = { response ->
                // Check if this is an error response
                if (isErrorResponse(response)) {
                    errorMessage = response.optString("data")
                } else {
                    val data = response.optJSONObject("data")
                    if (data != null) {
                        joinedRoomId = data.optString("room_id")
                    }
                }
                continuation.resumeWith(Result.success(Unit))
            }
            
            sendMessage(request.toString())
            
            // Timeout after 10 seconds
            kotlinx.coroutines.MainScope().launch {
                kotlinx.coroutines.delay(10000)
                if (pendingRequests.containsKey(requestId)) {
                    pendingRequests.remove(requestId)
                    errorMessage = "Request timed out"
                    continuation.resumeWith(Result.success(Unit))
                }
            }
        }
        
        Pair(joinedRoomId, errorMessage)
    }
}

/**
 * Composable screen for previewing and joining a room
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomJoinerScreen(
    roomLink: RoomLink,
    homeserverUrl: String,
    authToken: String,
    appViewModel: net.vrkknn.andromuks.AppViewModel,
    onDismiss: () -> Unit,
    onJoinSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var roomSummary by remember { mutableStateOf<RoomSummary?>(null) }
    var resolvedRoomId by remember { mutableStateOf<String?>(null) }
    var viaServers by remember { mutableStateOf(roomLink.viaServers) }
    var isJoining by remember { mutableStateOf(false) }
    
    // Handle back button
    BackHandler(enabled = true) {
        if (!isJoining) {
            onDismiss()
        }
    }
    
    // Load room summary on launch
    LaunchedEffect(roomLink) {
        try {
            var targetRoomId = roomLink.roomIdOrAlias
            var servers = roomLink.viaServers
            
            // If it's an alias, resolve it first
            if (roomLink.roomIdOrAlias.startsWith("#")) {
                appViewModel.resolveRoomAlias(roomLink.roomIdOrAlias) { result ->
                    if (result != null) {
                        targetRoomId = result.first
                        servers = result.second
                        resolvedRoomId = targetRoomId
                        viaServers = servers
                        
                        // Now get room summary
                        appViewModel.getRoomSummary(targetRoomId, servers) { summaryResult ->
                            val (summary, summaryError) = summaryResult ?: Pair(null, null)
                            if (summaryError != null) {
                                errorMessage = summaryError
                                roomSummary = null
                            } else if (summary != null) {
                                roomSummary = summary
                                errorMessage = null
                                // If already joined, navigate directly
                                if (summary.membership == "join") {
                                    Log.d("RoomJoiner", "Already joined to room ${summary.roomId}, navigating")
                                    onJoinSuccess(summary.roomId)
                                }
                            } else {
                                errorMessage = "Failed to load room information"
                            }
                            isLoading = false
                        }
                    } else {
                        errorMessage = "Failed to resolve room alias"
                        isLoading = false
                    }
                }
            } else {
                resolvedRoomId = targetRoomId
                // Get room summary
                appViewModel.getRoomSummary(targetRoomId, servers) { summaryResult ->
                    val (summary, summaryError) = summaryResult ?: Pair(null, null)
                    if (summaryError != null) {
                        errorMessage = summaryError
                        roomSummary = null
                    } else if (summary != null) {
                        roomSummary = summary
                        errorMessage = null
                        // If already joined, navigate directly
                        if (summary.membership == "join") {
                            Log.d("RoomJoiner", "Already joined to room ${summary.roomId}, navigating")
                            onJoinSuccess(summary.roomId)
                        }
                    } else {
                        errorMessage = "Failed to load room information"
                    }
                    isLoading = false
                }
            }
        } catch (e: Exception) {
            Log.e("RoomJoiner", "Error loading room summary", e)
            errorMessage = "Error: ${e.message}"
            isLoading = false
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room Preview") }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                errorMessage != null && roomSummary == null -> {
                    // Show error with option to join anyway or close
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Still allow trying to join if we have a room ID
                        if (resolvedRoomId != null) {
                            Text(
                                text = "Room information not available, but you can still try to join:",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = roomLink.roomIdOrAlias,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    isJoining = true
                                    appViewModel.joinRoomWithCallback(
                                        resolvedRoomId ?: roomLink.roomIdOrAlias,
                                        viaServers
                                    ) { result ->
                                        val (joinedRoomId, joinError) = result ?: Pair(null, null)
                                        if (joinError != null) {
                                            errorMessage = joinError
                                            isJoining = false
                                        } else if (joinedRoomId != null) {
                                            onJoinSuccess(joinedRoomId)
                                        } else {
                                            errorMessage = "Failed to join room"
                                            isJoining = false
                                        }
                                    }
                                },
                                enabled = !isJoining,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                            ) {
                                if (isJoining) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Text("Try to Join Anyway", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !isJoining,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text("Close", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                roomSummary != null -> {
                    RoomSummaryContent(
                        summary = roomSummary!!,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        isJoining = isJoining,
                        onJoinClick = {
                            isJoining = true
                            appViewModel.joinRoomWithCallback(
                                resolvedRoomId ?: roomLink.roomIdOrAlias,
                                viaServers
                            ) { result ->
                                val (joinedRoomId, joinError) = result ?: Pair(null, null)
                                if (joinError != null) {
                                    errorMessage = joinError
                                    isJoining = false
                                } else if (joinedRoomId != null) {
                                    onJoinSuccess(joinedRoomId)
                                } else {
                                    errorMessage = "Failed to join room"
                                    isJoining = false
                                }
                            }
                        },
                        onCancelClick = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomSummaryContent(
    summary: RoomSummary,
    homeserverUrl: String,
    authToken: String,
    isJoining: Boolean,
    onJoinClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Room Avatar
        if (summary.avatarUrl != null) {
            val avatarUrl = remember(summary.avatarUrl, homeserverUrl) {
                when {
                    summary.avatarUrl.startsWith("mxc://") -> {
                        MediaUtils.mxcToHttpUrl(summary.avatarUrl, homeserverUrl)
                    }
                    summary.avatarUrl.startsWith("_gomuks/") -> {
                        "$homeserverUrl/${summary.avatarUrl}"
                    }
                    else -> summary.avatarUrl
                }
            }
            
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .addHeader("Cookie", "gomuks_auth=$authToken")
                    .build(),
                contentDescription = "Room avatar",
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            // Default avatar
            Surface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Group,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Room Name
        Text(
            text = summary.name ?: summary.canonicalAlias ?: summary.roomId,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        // Canonical Alias (if different from name)
        if (summary.canonicalAlias != null && summary.name != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary.canonicalAlias,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Room Info Cards
        InfoCard(
            icon = Icons.Default.Group,
            title = "Members",
            value = "${summary.numJoinedMembers} ${if (summary.numJoinedMembers == 1) "member" else "members"}"
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        InfoCard(
            icon = if (summary.joinRule == "public") Icons.Default.Public else Icons.Default.Lock,
            title = "Join Rule",
            value = summary.joinRule.replaceFirstChar { it.uppercase() }
        )
        
        if (summary.worldReadable) {
            Spacer(modifier = Modifier.height(8.dp))
            InfoCard(
                icon = Icons.Default.Public,
                title = "History",
                value = "World readable"
            )
        }
        
        if (summary.guestCanJoin) {
            Spacer(modifier = Modifier.height(8.dp))
            InfoCard(
                icon = Icons.Default.Public,
                title = "Guest Access",
                value = "Guests can join"
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Action Buttons
        if (summary.membership != "join") {
            Button(
                onClick = onJoinClick,
                enabled = !isJoining,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (isJoining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Join Room", style = MaterialTheme.typography.titleMedium)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedButton(
                onClick = onCancelClick,
                enabled = !isJoining,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Cancel", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Text(
                text = "You are already a member of this room",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onCancelClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Close", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

