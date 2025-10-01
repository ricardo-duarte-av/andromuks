package net.vrkknn.andromuks.database.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.database.repository.MatrixRepository
import net.vrkknn.andromuks.TimelineEvent
import org.json.JSONObject
import org.json.JSONArray

/**
 * Enhanced WebSocket message handler with database integration
 * 
 * Handles WebSocket messages and integrates with the database layer
 * for persistent storage and efficient reconnection.
 */
class WebSocketManager(
    private val repository: MatrixRepository,
    private val scope: CoroutineScope
) {
    
    /**
     * Handle sync_complete message with database integration
     */
    suspend fun handleSyncComplete(jsonObject: JSONObject) {
        Log.d("WebSocketManager", "Processing sync_complete with database integration")
        
        try {
            val data = jsonObject.optJSONObject("data")
            val events = data?.optJSONArray("events")
            
            if (events != null) {
                val timelineEvents = mutableListOf<TimelineEvent>()
                
                for (i in 0 until events.length()) {
                    val eventJson = events.optJSONObject(i)
                    if (eventJson != null) {
                        val event = TimelineEvent.fromJson(eventJson)
                        timelineEvents.add(event)
                        
                        // Handle special event types
                        when (event.type) {
                            "m.room.redaction" -> {
                                handleRedactionEvent(event)
                            }
                            "m.room.message", "m.room.encrypted" -> {
                                // Regular message - will be processed by repository
                            }
                        }
                    }
                }
                
                // Process events by room
                val eventsByRoom = timelineEvents.groupBy { it.roomId }
                for ((roomId, roomEvents) in eventsByRoom) {
                    repository.processSyncComplete(roomId, roomEvents)
                }
                
                Log.d("WebSocketManager", "Processed ${timelineEvents.size} events across ${eventsByRoom.size} rooms")
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Error processing sync_complete", e)
        }
    }
    
    /**
     * Handle redaction events
     */
    private suspend fun handleRedactionEvent(redactionEvent: TimelineEvent) {
        try {
            val redactedEventId = redactionEvent.content?.optString("redacts")
            if (redactedEventId != null) {
                repository.handleRedactionEvent(redactionEvent)
                Log.d("WebSocketManager", "Processed redaction for event: $redactedEventId")
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Error processing redaction event", e)
        }
    }
    
    /**
     * Handle init_complete message
     */
    suspend fun handleInitComplete() {
        Log.d("WebSocketManager", "Received init_complete - initialization finished")
        // Update sync state to mark initialization complete
        scope.launch(Dispatchers.IO) {
            // This will be handled by the main AppViewModel
        }
    }
    
    /**
     * Handle client_state message
     */
    suspend fun handleClientState(userId: String?, deviceId: String?, homeserverUrl: String?) {
        Log.d("WebSocketManager", "Received client_state: user=$userId device=$deviceId homeserver=$homeserverUrl")
        
        if (userId != null && deviceId != null && homeserverUrl != null) {
            scope.launch(Dispatchers.IO) {
                repository.updateClientState(userId, deviceId, homeserverUrl, "")
            }
        }
    }
    
    /**
     * Handle image_auth_token message
     */
    suspend fun handleImageAuthToken(token: String) {
        Log.d("WebSocketManager", "Received image_auth_token")
        scope.launch(Dispatchers.IO) {
            // Update sync state with image auth token
            // This will be handled by the main AppViewModel
        }
    }
    
    /**
     * Handle send_complete message
     */
    suspend fun handleSendComplete(jsonObject: JSONObject) {
        Log.d("WebSocketManager", "Processing send_complete")
        
        try {
            val data = jsonObject.optJSONObject("data")
            val event = data?.optJSONObject("event")
            
            if (event != null) {
                val timelineEvent = TimelineEvent.fromJson(event)
                scope.launch(Dispatchers.IO) {
                    repository.insertEvent(timelineEvent)
                }
                Log.d("WebSocketManager", "Added send_complete event to database: ${timelineEvent.eventId}")
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Error processing send_complete", e)
        }
    }
    
    /**
     * Handle typing indicators
     */
    suspend fun handleTypingIndicator(roomId: String, userIds: List<String>) {
        Log.d("WebSocketManager", "Received typing indicator for room=$roomId, users=$userIds")
        // Typing indicators are handled by the main AppViewModel
        // This is just for logging and potential future database storage
    }
    
    /**
     * Handle error messages
     */
    suspend fun handleError(requestId: Int, errorMessage: String) {
        Log.d("WebSocketManager", "Received error for requestId=$requestId: $errorMessage")
        // Error handling is managed by the main AppViewModel
    }
    
    /**
     * Update sync state with received request ID
     */
    suspend fun updateSyncState(requestId: Int) {
        if (requestId != 0) {
            scope.launch(Dispatchers.IO) {
                // Update last received ID in sync state
                // This will be handled by the main AppViewModel
            }
        }
    }
}
