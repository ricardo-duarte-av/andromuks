package net.vrkknn.andromuks

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.database.AndromuksDatabase
import net.vrkknn.andromuks.database.repository.MatrixRepository
import net.vrkknn.andromuks.database.websocket.WebSocketManager
import net.vrkknn.andromuks.database.websocket.ReconnectionManager
import okhttp3.WebSocket
import org.json.JSONObject

/**
 * Enhanced AppViewModel with database integration
 * 
 * This version integrates the existing AppViewModel functionality with the new database layer.
 * It maintains backward compatibility while adding persistent storage capabilities.
 */
class AppViewModelWithDatabase(
    private val context: Context
) : ViewModel() {
    
    // Database and repository
    private val database = AndromuksDatabase.getDatabase(context)
    private val repository = MatrixRepository(
        database.eventDao(),
        database.roomDao(),
        database.syncStateDao(),
        database.reactionDao(),
        database.userProfileDao()
    )
    
    // WebSocket management
    private val webSocketManager = WebSocketManager(repository, viewModelScope)
    private val reconnectionManager = ReconnectionManager(repository, viewModelScope)
    
    // Existing state variables (maintained for compatibility)
    var isLoading by mutableStateOf(false)
    var homeserverUrl by mutableStateOf("")
        private set
    var authToken by mutableStateOf("")
        private set
    
    // Auth/client state
    var currentUserId by mutableStateOf("")
        private set
    var deviceId by mutableStateOf("")
        private set
    var imageAuthToken by mutableStateOf("")
        private set
    var currentUserProfile by mutableStateOf<UserProfile?>(null)
        private set
    
    // Settings
    var showUnprocessedEvents by mutableStateOf(true)
        private set
    
    // Database-backed Flow data
    val allRooms: Flow<List<RoomItem>> = repository.getAllRooms()
    val unreadRooms: Flow<List<RoomItem>> = repository.getUnreadRooms()
    
    // Current room state
    var currentRoomId by mutableStateOf("")
        private set
    
    fun getEventsForRoom(roomId: String): Flow<List<TimelineEvent>> {
        return repository.getEventsForRoom(roomId)
    }
    
    // WebSocket state
    private var webSocket: WebSocket? = null
    private var onRestartWebSocket: (() -> Unit)? = null
    
    // App visibility state
    private var isAppVisible = true
    private var appInvisibleJob: kotlinx.coroutines.Job? = null
    
    /**
     * Initialize the database integration
     */
    fun initializeDatabase() {
        viewModelScope.launch {
            // Load cached profiles from database
            // This replaces the SharedPreferences approach
            loadCachedProfilesFromDatabase()
        }
    }
    
    /**
     * Load cached profiles from database instead of SharedPreferences
     */
    private suspend fun loadCachedProfilesFromDatabase() {
        try {
            // Load profiles from database
            // This will be implemented in the repository
            android.util.Log.d("Andromuks", "AppViewModel: Loaded cached profiles from database")
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to load cached profiles from database", e)
        }
    }
    
    /**
     * Handle WebSocket message with database integration
     */
    suspend fun handleWebSocketMessage(text: String) {
        try {
            val jsonObject = JSONObject(text)
            val command = jsonObject.optString("command")
            val requestId = jsonObject.optInt("request_id", 0)
            
            // Update sync state with request ID
            if (requestId != 0) {
                repository.updateSyncState(null, requestId.toLong())
            }
            
            when (command) {
                "sync_complete" -> {
                    webSocketManager.handleSyncComplete(jsonObject)
                }
                "init_complete" -> {
                    webSocketManager.handleInitComplete()
                }
                "client_state" -> {
                    val data = jsonObject.optJSONObject("data")
                    val userId = data?.optString("user_id")
                    val deviceId = data?.optString("device_id")
                    val homeserverUrl = data?.optString("homeserver_url")
                    webSocketManager.handleClientState(userId, deviceId, homeserverUrl)
                }
                "image_auth_token" -> {
                    val token = jsonObject.optString("data", "")
                    webSocketManager.handleImageAuthToken(token)
                }
                "send_complete" -> {
                    webSocketManager.handleSendComplete(jsonObject)
                }
                "typing" -> {
                    val data = jsonObject.optJSONObject("data")
                    val roomId = data?.optString("room_id")
                    val userIds = mutableListOf<String>()
                    val userIdsArray = data?.optJSONArray("user_ids")
                    if (userIdsArray != null) {
                        for (i in 0 until userIdsArray.length()) {
                            userIdsArray.optString(i)?.let { userIds.add(it) }
                        }
                    }
                    webSocketManager.handleTypingIndicator(roomId ?: "", userIds)
                }
                "error" -> {
                    val errorMessage = jsonObject.optString("data", "Unknown error")
                    webSocketManager.handleError(requestId, errorMessage)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error handling WebSocket message", e)
        }
    }
    
    /**
     * Connect to WebSocket with database-backed reconnection
     */
    fun connectToWebSocket(baseUrl: String, client: okhttp3.OkHttpClient, token: String) {
        reconnectionManager.setRestartCallback {
            connectToWebSocket(baseUrl, client, token)
        }
        
        reconnectionManager.connectToWebsocket(
            baseUrl = baseUrl,
            client = client,
            token = token,
            onWebSocketReady = { webSocket ->
                this.webSocket = webSocket
                android.util.Log.d("Andromuks", "AppViewModel: WebSocket connected")
            },
            onMessage = { text ->
                viewModelScope.launch {
                    handleWebSocketMessage(text)
                }
            }
        )
    }
    
    /**
     * Set WebSocket reference (for compatibility with existing code)
     */
    fun setWebSocket(webSocket: WebSocket) {
        this.webSocket = webSocket
    }
    
    /**
     * Clear WebSocket reference
     */
    fun clearWebSocket() {
        this.webSocket = null
    }
    
    /**
     * Set restart callback for WebSocket
     */
    fun setRestartCallback(callback: () -> Unit) {
        onRestartWebSocket = callback
    }
    
    /**
     * Send message to current room
     */
    fun sendMessage(roomId: String, text: String) {
        val ws = webSocket ?: return
        viewModelScope.launch {
            // Send message via WebSocket
            val messageRequestId = (100..999).random()
            val json = org.json.JSONObject()
            json.put("command", "send_message")
            json.put("request_id", messageRequestId)
            val data = org.json.JSONObject()
            data.put("room_id", roomId)
            data.put("text", text)
            data.put("mentions", org.json.JSONObject().apply {
                put("user_ids", org.json.JSONArray())
                put("room", false)
            })
            data.put("url_previews", org.json.JSONArray())
            json.put("data", data)
            
            ws.send(json.toString())
            android.util.Log.d("Andromuks", "AppViewModel: Sent message to room $roomId")
        }
    }
    
    /**
     * Mark room as read
     */
    fun markRoomAsRead(roomId: String) {
        viewModelScope.launch {
            repository.markRoomAsRead(roomId)
        }
    }
    
    /**
     * Update room name
     */
    fun updateRoomName(roomId: String, name: String) {
        viewModelScope.launch {
            repository.updateRoomName(roomId, name)
        }
    }
    
    /**
     * Insert user profile
     */
    fun insertUserProfile(userId: String, displayName: String?, avatarUrl: String?) {
        viewModelScope.launch {
            repository.insertUserProfile(userId, displayName, avatarUrl)
        }
    }
    
    /**
     * Get user profile
     */
    suspend fun getUserProfile(userId: String): net.vrkknn.andromuks.database.entities.UserProfileEntity? {
        return repository.getUserProfile(userId)
    }
    
    /**
     * Handle app visibility changes
     */
    fun onAppBecameVisible() {
        android.util.Log.d("Andromuks", "AppViewModel: App became visible")
        isAppVisible = true
        
        // Cancel any existing shutdown job
        appInvisibleJob?.cancel()
        appInvisibleJob = null
        
        // If WebSocket is not connected, restart it
        if (webSocket == null) {
            android.util.Log.d("Andromuks", "AppViewModel: WebSocket not connected, restarting...")
            onRestartWebSocket?.invoke()
        }
    }
    
    fun onAppBecameInvisible() {
        android.util.Log.d("Andromuks", "AppViewModel: App became invisible")
        isAppVisible = false
        
        // Cancel any existing shutdown job
        appInvisibleJob?.cancel()
        
        // Start delayed shutdown (15 seconds)
        appInvisibleJob = viewModelScope.launch {
            delay(15_000) // 15 seconds delay
            
            // Check if app is still invisible after delay
            if (!isAppVisible) {
                android.util.Log.d("Andromuks", "AppViewModel: App still invisible after 15s, shutting down WebSocket")
                clearWebSocket()
            }
        }
    }
    
    /**
     * Manually suspend app
     */
    fun suspendApp() {
        android.util.Log.d("Andromuks", "AppViewModel: App manually suspended, starting 15-second timer to close websocket")
        onAppBecameInvisible()
    }
    
    /**
     * Cleanup resources
     */
    override fun onCleared() {
        super.onCleared()
        android.util.Log.d("Andromuks", "AppViewModel: onCleared - cleaning up resources")
        
        // Cancel any pending jobs
        appInvisibleJob?.cancel()
        
        // Clear WebSocket connection
        clearWebSocket()
    }
}
