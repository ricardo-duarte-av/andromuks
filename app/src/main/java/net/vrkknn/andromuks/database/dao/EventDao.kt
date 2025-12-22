package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.vrkknn.andromuks.database.entities.EventEntity

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: EventEntity)

    @Query("SELECT * FROM events WHERE roomId = :roomId ORDER BY timelineRowId ASC, timestamp ASC LIMIT :limit")
    suspend fun getEventsForRoomAsc(roomId: String, limit: Int): List<EventEntity>

    @Query("""
        SELECT * FROM events 
        WHERE roomId = :roomId 
        ORDER BY 
            timestamp DESC,
            timelineRowId DESC,
            eventId DESC
        LIMIT :limit
    """)
    suspend fun getEventsForRoomDesc(roomId: String, limit: Int): List<EventEntity>

    @Query(
        """
        SELECT * FROM events
        WHERE roomId = :roomId
          AND timelineRowId > 0
          AND timelineRowId < :maxTimelineRowId
        ORDER BY timelineRowId DESC, timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getEventsBeforeRowId(
        roomId: String,
        maxTimelineRowId: Long,
        limit: Int
    ): List<EventEntity>

    @Query(
        """
        SELECT * FROM events
        WHERE roomId = :roomId
          AND timestamp > 0
          AND timestamp < :maxTimestamp
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getEventsBeforeTimestamp(
        roomId: String,
        maxTimestamp: Long,
        limit: Int
    ): List<EventEntity>

    @Query("DELETE FROM events WHERE roomId = :roomId AND eventId IN (:eventIds)")
    suspend fun deleteEvents(roomId: String, eventIds: List<String>)

    @Query("DELETE FROM events WHERE roomId = :roomId AND timelineRowId > 0 AND eventId NOT IN (SELECT eventId FROM events WHERE roomId = :roomId ORDER BY timelineRowId DESC, timestamp DESC LIMIT :keep)")
    suspend fun trimRoomEvents(roomId: String, keep: Int)
    
    @Query("DELETE FROM events WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteEventsOlderThan(cutoffTimestamp: Long): Int
    
    @Query("DELETE FROM events")
    suspend fun deleteAll()
    
    @Query("DELETE FROM events WHERE roomId = :roomId")
    suspend fun deleteAllForRoom(roomId: String)
    
    @Query("SELECT COUNT(*) FROM events WHERE roomId = :roomId")
    suspend fun getEventCountForRoom(roomId: String): Int
    
    @Query("SELECT SUM(LENGTH(rawJson)) FROM events WHERE roomId = :roomId")
    suspend fun getTotalSizeForRoom(roomId: String): Long?
    
    @Query("SELECT MAX(timestamp) FROM events WHERE roomId = :roomId AND timestamp > 0")
    suspend fun getLastEventTimestamp(roomId: String): Long?
    
    /**
     * Observe the latest event timestamp for a room.
     * This Flow automatically emits when new events are inserted into the database.
     * Returns null if no events exist for the room.
     * 
     * This is more efficient than polling because it only triggers on actual DB changes.
     */
    @Query("SELECT MAX(timestamp) FROM events WHERE roomId = :roomId AND timestamp > 0")
    fun observeLastEventTimestamp(roomId: String): Flow<Long?>
    
    @Query("SELECT * FROM events WHERE roomId = :roomId AND eventId = :eventId LIMIT 1")
    suspend fun getEventById(roomId: String, eventId: String): EventEntity?
    
    /**
     * Get the event with the smallest timelineRowId for a room (can be negative).
     * Used for pagination to find the oldest event in the timeline.
     * Note: timelineRowId can be negative for state events or events from certain syncs.
     */
    @Query("""
        SELECT * FROM events 
        WHERE roomId = :roomId 
        ORDER BY timelineRowId ASC, timestamp ASC 
        LIMIT 1
    """)
    suspend fun getOldestEventByTimelineRowId(roomId: String): EventEntity?
    
    @Query("SELECT * FROM events WHERE roomId = :roomId AND relatesToEventId = :relatesToEventId ORDER BY timestamp ASC")
    suspend fun getEventsByRelatesTo(roomId: String, relatesToEventId: String): List<EventEntity>

    @Query("""
        SELECT * FROM events 
        WHERE roomId = :roomId 
        ORDER BY timestamp DESC, timelineRowId DESC, eventId DESC 
        LIMIT 1
    """)
    suspend fun getMostRecentEventForRoom(roomId: String): EventEntity?
    
    /**
     * Get the last message event (m.room.message or m.room.encrypted) for a room.
     * Used for room summary/preview instead of scanning JSON.
     * 
     * E2EE: In encrypted rooms, messages have type='m.room.encrypted' with decryptedType='m.room.message'.
     * The backend already decrypts messages for us, so decrypted content is always available.
     * We must query for both:
     * - Regular messages: type = 'm.room.message'
     * - Encrypted messages: type = 'm.room.encrypted' AND decryptedType = 'm.room.message'
     * 
     * Note: If we only query for 'm.room.message' in E2EE rooms, we won't find any messages
     * because all messages have type='m.room.encrypted' in those rooms.
     */
    @Query("""
        SELECT * FROM events 
        WHERE roomId = :roomId 
            AND (
              type = 'm.room.message' 
              OR (type = 'm.room.encrypted' AND (decryptedType = 'm.room.message' OR decryptedType = 'm.text'))
            )
            AND timestamp > 0
        ORDER BY timestamp DESC, timelineRowId DESC, eventId DESC 
        LIMIT 1
    """)
    suspend fun getLastMessageForRoom(roomId: String): EventEntity?

    /**
     * Batched variant for fetching last message events for multiple rooms at once.
     * Returns rows ordered by roomId then newest-first so the caller can pick the first per room.
     * This avoids one query per room in the room list.
     */
    @Query("""
        SELECT * FROM events 
        WHERE roomId IN (:roomIds)
            AND (
              type = 'm.room.message' 
              OR (type = 'm.room.encrypted' AND (decryptedType = 'm.room.message' OR decryptedType = 'm.text'))
            )
            AND timestamp > 0
        ORDER BY roomId ASC, timestamp DESC, timelineRowId DESC, eventId DESC
    """)
    suspend fun getLastMessagesForRooms(roomIds: List<String>): List<EventEntity>

    /**
     * Fetch the latest message-like event for every room in one query.
     * Uses a window function to pick the newest by timestamp, then timelineRowId, then eventId.
     */
    @Query("""
        SELECT * FROM (
            SELECT e.*,
                   ROW_NUMBER() OVER (
                       PARTITION BY e.roomId
                       ORDER BY e.timestamp DESC, e.timelineRowId DESC, e.eventId DESC
                   ) AS rn
            FROM events e
            WHERE
                (
                    e.type = 'm.room.message'
                    OR (e.type = 'm.room.encrypted' AND (e.decryptedType = 'm.room.message' OR e.decryptedType = 'm.text'))
                )
        )
        WHERE rn = 1
    """)
    suspend fun getLatestMessagesForAllRooms(): List<EventEntity>
}


