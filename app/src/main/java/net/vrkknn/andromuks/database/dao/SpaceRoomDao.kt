package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.SpaceRoomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceRoomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(spaceRoom: SpaceRoomEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(spaceRooms: List<SpaceRoomEntity>)

    @Query("SELECT * FROM space_rooms WHERE spaceId = :spaceId")
    suspend fun getRoomsForSpace(spaceId: String): List<SpaceRoomEntity>

    @Query("SELECT * FROM space_rooms WHERE spaceId = :spaceId")
    fun getRoomsForSpaceFlow(spaceId: String): Flow<List<SpaceRoomEntity>>

    @Query("SELECT * FROM space_rooms WHERE childId = :childId")
    suspend fun getSpacesForRoom(childId: String): List<SpaceRoomEntity>

    @Query("DELETE FROM space_rooms WHERE spaceId = :spaceId")
    suspend fun deleteRoomsForSpace(spaceId: String)

    @Query("DELETE FROM space_rooms WHERE spaceId = :spaceId AND childId = :childId")
    suspend fun deleteRoomFromSpace(spaceId: String, childId: String)

    @Query("DELETE FROM space_rooms")
    suspend fun deleteAllSpaceRooms()
    
    @Query("SELECT * FROM space_rooms")
    suspend fun getAllRoomsForAllSpaces(): List<SpaceRoomEntity>
    
    @Query("SELECT * FROM space_rooms")
    fun getAllRoomsForAllSpacesFlow(): Flow<List<SpaceRoomEntity>>
    
    @Query("DELETE FROM space_rooms WHERE spaceId NOT IN (SELECT spaceId FROM spaces)")
    suspend fun deleteOrphanedSpaceRooms(): Int
}

