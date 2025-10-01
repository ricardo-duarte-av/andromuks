package net.vrkknn.andromuks.database

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.database.repository.MatrixRepository
import net.vrkknn.andromuks.database.websocket.WebSocketManager
import net.vrkknn.andromuks.database.websocket.ReconnectionManager
import org.json.JSONObject

/**
 * Database integration for AppViewModel
 * 
 * Provides methods to integrate the existing AppViewModel with the new database layer.
 * This allows for gradual migration from in-memory to database storage.
 */
class AppViewModelDatabaseIntegration(
    private val context: Context,
    private val repository: MatrixRepository
) {
    
    private val webSocketManager = WebSocketManager(repository, viewModelScope)
    private val reconnectionManager = ReconnectionManager(repository, viewModelScope)
    
    // Flow-based data for UI
    fun getAllRoomsFlow(): Flow<List<RoomItem>> {
        return repository.getAllRooms()
    }
    
    fun getUnreadRoomsFlow(): Flow<List<RoomItem>> {
        return repository.getUnreadRooms()
    }
    
    fun getEventsForRoomFlow(roomId: String): Flow<List<TimelineEvent>> {
        return repository.getEventsForRoom(roomId)
    }
    
    // WebSocket message handling with database integration
    suspend fun handleWebSocketMessage(text: String) {
        try {
            val jsonObject = JSONObject(text)
            val command = jsonObject.optString("command")
            val requestId = jsonObject.optInt("request_id", 0)
            
            // Update sync state with request ID
            if (requestId != 0) {
                webSocketManager.updateSyncState(requestId)
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
            android.util.Log.e("DatabaseIntegration", "Error handling WebSocket message", e)
        }
    }
    
    // Reconnection management
    fun setupReconnection(callback: () -> Unit) {
        reconnectionManager.setRestartCallback(callback)
    }
    
    suspend fun connectWithReconnection(
        baseUrl: String,
        client: okhttp3.OkHttpClient,
        token: String,
        onWebSocketReady: (okhttp3.WebSocket) -> Unit
    ) {
        reconnectionManager.connectToWebsocket(
            baseUrl = baseUrl,
            client = client,
            token = token,
            onWebSocketReady = onWebSocketReady,
            onMessage = { text ->
                viewModelScope.launch {
                    handleWebSocketMessage(text)
                }
            }
        )
    }
    
    // Room management
    suspend fun updateRoomName(roomId: String, name: String) {
        repository.updateRoomName(roomId, name)
    }
    
    suspend fun updateUnreadCount(roomId: String, unreadCount: Int) {
        repository.updateUnreadCount(roomId, unreadCount)
    }
    
    suspend fun markRoomAsRead(roomId: String) {
        repository.markRoomAsRead(roomId)
    }
    
    // Event management
    suspend fun insertEvent(event: TimelineEvent) {
        repository.insertEvent(event)
    }
    
    suspend fun insertEvents(events: List<TimelineEvent>) {
        repository.insertEvents(events)
    }
    
    // User profile management
    suspend fun insertUserProfile(userId: String, displayName: String?, avatarUrl: String?) {
        repository.insertUserProfile(userId, displayName, avatarUrl)
    }
    
    suspend fun getUserProfile(userId: String): net.vrkknn.andromuks.database.entities.UserProfileEntity? {
        return repository.getUserProfile(userId)
    }
    
    // Reaction management
    suspend fun insertReaction(reaction: net.vrkknn.andromuks.ReactionEvent, roomId: String) {
        repository.insertReaction(reaction, roomId)
    }
    
    suspend fun getReactionsForEvent(eventId: String): List<net.vrkknn.andromuks.ReactionEvent> {
        return repository.getReactionsForEvent(eventId)
    }
    
    // Sync state management
    suspend fun updateSyncState(runId: String?, lastReceivedId: Long) {
        repository.updateSyncState(runId, lastReceivedId)
    }
    
    suspend fun updateClientState(userId: String, deviceId: String, homeserverUrl: String, imageAuthToken: String) {
        repository.updateClientState(userId, deviceId, homeserverUrl, imageAuthToken)
    }
    
    // Database maintenance
    suspend fun cleanupOldData(cutoffTimestamp: Long) {
        viewModelScope.launch {
            // Cleanup old events, reactions, etc.
            // This will be implemented in the repository
        }
    }
}
