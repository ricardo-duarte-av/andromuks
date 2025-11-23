package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.PendingRoomEntity

@Dao
interface PendingRoomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pendingRoom: PendingRoomEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pendingRooms: List<PendingRoomEntity>)
    
    @Query("SELECT * FROM pending_rooms ORDER BY timestamp ASC")
    suspend fun getAllPendingRooms(): List<PendingRoomEntity>
    
    @Query("SELECT * FROM pending_rooms WHERE roomId = :roomId LIMIT 1")
    suspend fun getPendingRoom(roomId: String): PendingRoomEntity?
    
    @Query("DELETE FROM pending_rooms WHERE roomId = :roomId")
    suspend fun deletePendingRoom(roomId: String)
    
    @Query("DELETE FROM pending_rooms WHERE roomId IN (:roomIds)")
    suspend fun deletePendingRooms(roomIds: List<String>)
    
    @Query("DELETE FROM pending_rooms")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM pending_rooms")
    suspend fun getPendingCount(): Int
}

