package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.RoomStateEntity

@Dao
interface RoomStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: RoomStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<RoomStateEntity>)

    @Query("SELECT * FROM room_state WHERE roomId = :roomId")
    suspend fun get(roomId: String): RoomStateEntity?
    
    @Query("SELECT * FROM room_state WHERE roomId IN (:roomIds)")
    suspend fun getRoomStatesByIds(roomIds: List<String>): List<RoomStateEntity>
    
    @Query("SELECT * FROM room_state")
    suspend fun getAllRoomStates(): List<RoomStateEntity>
    
    @Query("DELETE FROM room_state WHERE roomId = :roomId")
    suspend fun deleteForRoom(roomId: String)
    
    @Query("DELETE FROM room_state")
    suspend fun deleteAll()
}


