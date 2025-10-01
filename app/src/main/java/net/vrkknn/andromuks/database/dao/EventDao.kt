package net.vrkknn.andromuks.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.vrkknn.andromuks.database.entities.EventEntity

/**
 * Data Access Object for timeline events
 * 
 * Provides efficient queries for event retrieval with support for:
 * - Room-based filtering
 * - Event type filtering
 * - Redaction handling
 * - Pagination
 */
@Dao
interface EventDao {
    
    // Basic CRUD operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<EventEntity>)
    
    @Update
    suspend fun updateEvent(event: EventEntity)
    
    @Delete
    suspend fun deleteEvent(event: EventEntity)
    
    @Query("DELETE FROM events WHERE roomId = :roomId")
    suspend fun deleteEventsForRoom(roomId: String)
    
    // Event retrieval
    @Query("SELECT * FROM events WHERE eventId = :eventId LIMIT 1")
    suspend fun getEventById(eventId: String): EventEntity?
    
    @Query("SELECT * FROM events WHERE roomId = :roomId ORDER BY timestamp ASC")
    fun getEventsForRoom(roomId: String): Flow<List<EventEntity>>
    
    @Query("SELECT * FROM events WHERE roomId = :roomId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEventsForRoom(roomId: String, limit: Int = 50): List<EventEntity>
    
    @Query("SELECT * FROM events WHERE roomId = :roomId AND timestamp > :sinceTimestamp ORDER BY timestamp ASC")
    fun getEventsSince(roomId: String, sinceTimestamp: Long): Flow<List<EventEntity>>
    
    // Event type filtering
    @Query("SELECT * FROM events WHERE roomId = :roomId AND type = :eventType ORDER BY timestamp DESC")
    fun getEventsByType(roomId: String, eventType: String): Flow<List<EventEntity>>
    
    @Query("SELECT * FROM events WHERE roomId = :roomId AND type IN (:eventTypes) ORDER BY timestamp DESC")
    fun getEventsByTypes(roomId: String, eventTypes: List<String>): Flow<List<EventEntity>>
    
    // Redaction handling
    @Query("SELECT * FROM events WHERE roomId = :roomId AND isRedacted = 0 ORDER BY timestamp DESC")
    fun getNonRedactedEvents(roomId: String): Flow<List<EventEntity>>
    
    @Query("UPDATE events SET isRedacted = 1, redactedBy = :redactedBy, redactedAt = :redactedAt WHERE eventId = :eventId")
    suspend fun markEventAsRedacted(eventId: String, redactedBy: String, redactedAt: Long)
    
    // Message events only (for timeline)
    @Query("SELECT * FROM events WHERE roomId = :roomId AND type IN ('m.room.message', 'm.room.encrypted') AND isRedacted = 0 ORDER BY timestamp ASC")
    fun getMessageEvents(roomId: String): Flow<List<EventEntity>>
    
    // Search functionality
    @Query("SELECT * FROM events WHERE roomId = :roomId AND content LIKE :searchTerm AND isRedacted = 0 ORDER BY timestamp DESC")
    suspend fun searchEvents(roomId: String, searchTerm: String): List<EventEntity>
    
    // Statistics
    @Query("SELECT COUNT(*) FROM events WHERE roomId = :roomId")
    suspend fun getEventCountForRoom(roomId: String): Int
    
    @Query("SELECT COUNT(*) FROM events WHERE roomId = :roomId AND isRedacted = 0")
    suspend fun getNonRedactedEventCountForRoom(roomId: String): Int
    
    // Latest event for room preview
    @Query("SELECT * FROM events WHERE roomId = :roomId AND isRedacted = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEventForRoom(roomId: String): EventEntity?
    
    // Cleanup operations
    @Query("DELETE FROM events WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldEvents(cutoffTimestamp: Long)
    
    @Query("DELETE FROM events WHERE roomId = :roomId AND timestamp < :cutoffTimestamp")
    suspend fun deleteOldEventsForRoom(roomId: String, cutoffTimestamp: Long)
}
