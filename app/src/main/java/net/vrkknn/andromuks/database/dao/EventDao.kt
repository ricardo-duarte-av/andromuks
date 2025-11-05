package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.EventEntity

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: EventEntity)

    @Query("SELECT * FROM events WHERE roomId = :roomId ORDER BY timelineRowId ASC, timestamp ASC LIMIT :limit")
    suspend fun getEventsForRoomAsc(roomId: String, limit: Int): List<EventEntity>

    @Query("SELECT * FROM events WHERE roomId = :roomId ORDER BY timelineRowId DESC, timestamp DESC LIMIT :limit")
    suspend fun getEventsForRoomDesc(roomId: String, limit: Int): List<EventEntity>

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
    
    @Query("SELECT * FROM events WHERE roomId = :roomId AND eventId = :eventId LIMIT 1")
    suspend fun getEventById(roomId: String, eventId: String): EventEntity?
    
    @Query("SELECT * FROM events WHERE roomId = :roomId AND relatesToEventId = :relatesToEventId ORDER BY timestamp ASC")
    suspend fun getEventsByRelatesTo(roomId: String, relatesToEventId: String): List<EventEntity>
}


