package net.vrkknn.andromuks.database.repository

import kotlinx.coroutines.flow.Flow
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.database.dao.*
import net.vrkknn.andromuks.database.entities.*

/**
 * Repository for Matrix data operations
 * 
 * Provides a clean interface between the UI layer and database layer.
 * Handles complex operations that involve multiple DAOs.
 */
class MatrixRepository(
    private val eventDao: EventDao,
    private val roomDao: RoomDao,
    private val syncStateDao: SyncStateDao,
    private val reactionDao: ReactionDao,
    private val userProfileDao: UserProfileDao
) {
    
    // Event operations
    suspend fun insertEvent(event: TimelineEvent) {
        eventDao.insertEvent(EventEntity.fromTimelineEvent(event))
    }
    
    suspend fun insertEvents(events: List<TimelineEvent>) {
        eventDao.insertEvents(events.map { EventEntity.fromTimelineEvent(it) })
    }
    
    fun getEventsForRoom(roomId: String): Flow<List<TimelineEvent>> {
        return eventDao.getEventsForRoom(roomId).map { entities ->
            entities.map { it.toTimelineEvent() }
        }
    }
    
    suspend fun getRecentEventsForRoom(roomId: String, limit: Int = 50): List<TimelineEvent> {
        return eventDao.getRecentEventsForRoom(roomId, limit).map { it.toTimelineEvent() }
    }
    
    suspend fun markEventAsRedacted(eventId: String, redactedBy: String, redactedAt: Long) {
        eventDao.markEventAsRedacted(eventId, redactedBy, redactedAt)
    }
    
    // Room operations
    suspend fun insertRoom(room: RoomItem) {
        roomDao.insertRoom(RoomEntity.fromRoomItem(room))
    }
    
    suspend fun insertRooms(rooms: List<RoomItem>) {
        roomDao.insertRooms(rooms.map { RoomEntity.fromRoomItem(it) })
    }
    
    fun getAllRooms(): Flow<List<RoomItem>> {
        return roomDao.getAllRooms().map { entities ->
            entities.map { it.toRoomItem() }
        }
    }
    
    fun getUnreadRooms(): Flow<List<RoomItem>> {
        return roomDao.getUnreadRooms().map { entities ->
            entities.map { it.toRoomItem() }
        }
    }
    
    suspend fun updateRoomName(roomId: String, name: String) {
        roomDao.updateRoomName(roomId, name)
    }
    
    suspend fun updateUnreadCount(roomId: String, unreadCount: Int) {
        roomDao.updateUnreadCount(roomId, unreadCount)
    }
    
    suspend fun markRoomAsRead(roomId: String) {
        roomDao.markRoomAsRead(roomId)
    }
    
    // Sync state operations
    suspend fun updateSyncState(runId: String?, lastReceivedId: Long) {
        val currentState = syncStateDao.getCurrentSyncState()
        if (currentState == null) {
            syncStateDao.insertSyncState(SyncStateEntity(
                runId = runId,
                lastReceivedId = lastReceivedId
            ))
        } else {
            syncStateDao.updateLastReceivedId(lastReceivedId)
            runId?.let { syncStateDao.updateRunId(it) }
        }
    }
    
    suspend fun getSyncParameters(): Pair<String?, Long>? {
        return syncStateDao.getSyncParameters()
    }
    
    suspend fun updateClientState(userId: String, deviceId: String, homeserverUrl: String, imageAuthToken: String) {
        syncStateDao.updateClientState(userId, deviceId, homeserverUrl, imageAuthToken)
    }
    
    // User profile operations
    suspend fun insertUserProfile(userId: String, displayName: String?, avatarUrl: String?) {
        userProfileDao.insertProfile(UserProfileEntity(
            userId = userId,
            displayName = displayName,
            avatarUrl = avatarUrl
        ))
    }
    
    suspend fun getUserProfile(userId: String): UserProfileEntity? {
        return userProfileDao.getProfileByUserId(userId)
    }
    
    // Reaction operations
    suspend fun insertReaction(reaction: net.vrkknn.andromuks.ReactionEvent, roomId: String) {
        reactionDao.insertReaction(ReactionEntity.fromReactionEvent(reaction, roomId))
    }
    
    suspend fun getReactionsForEvent(eventId: String): List<net.vrkknn.andromuks.ReactionEvent> {
        return reactionDao.getReactionsForEventInRoom("", eventId).map { it.toReactionEvent() }
    }
    
    // Complex operations
    suspend fun processSyncComplete(roomId: String, events: List<TimelineEvent>) {
        // Insert events
        insertEvents(events)
        
        // Update room with latest event
        val latestEvent = events.maxByOrNull { it.timestamp }
        if (latestEvent != null) {
            roomDao.updateMessagePreview(
                roomId = roomId,
                messagePreview = extractMessagePreview(latestEvent),
                messageSender = latestEvent.sender,
                timestamp = latestEvent.timestamp
            )
        }
    }
    
    suspend fun handleRedactionEvent(redactionEvent: TimelineEvent) {
        val redactedEventId = redactionEvent.content?.optString("redacts")
        if (redactedEventId != null) {
            markEventAsRedacted(redactedEventId, redactionEvent.sender, redactionEvent.timestamp)
        }
    }
    
    private fun extractMessagePreview(event: TimelineEvent): String? {
        return when (event.type) {
            "m.room.message" -> {
                event.content?.optString("body")?.takeIf { it.isNotBlank() }
            }
            "m.room.encrypted" -> {
                if (event.decryptedType == "m.room.message") {
                    event.decrypted?.optString("body")?.takeIf { it.isNotBlank() }
                } else {
                    "[Encrypted message]"
                }
            }
            else -> "[${event.type}]"
        }
    }
}
