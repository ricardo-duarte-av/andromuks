package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.RoomSummaryEntity

@Dao
interface RoomSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: RoomSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(summaries: List<RoomSummaryEntity>)

    @Query("SELECT * FROM room_summary ORDER BY lastTimestamp DESC LIMIT :limit")
    suspend fun getTopRooms(limit: Int): List<RoomSummaryEntity>
    
    @Query("SELECT * FROM room_summary ORDER BY lastTimestamp DESC")
    suspend fun getAllRooms(): List<RoomSummaryEntity>
    
    @Query("DELETE FROM room_summary WHERE roomId = :roomId")
    suspend fun deleteForRoom(roomId: String)
    
    @Query("DELETE FROM room_summary WHERE roomId NOT IN (SELECT DISTINCT roomId FROM events)")
    suspend fun deleteOrphanedSummaries(): Int
    
    @Query("DELETE FROM room_summary")
    suspend fun deleteAll()
}


