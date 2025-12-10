package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.RoomListSummaryEntity

@Dao
interface RoomListSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RoomListSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RoomListSummaryEntity>)

    @Query("SELECT * FROM room_list_summary WHERE roomId IN (:roomIds)")
    suspend fun getRoomSummariesByIds(roomIds: List<String>): List<RoomListSummaryEntity>

    @Query("SELECT * FROM room_list_summary WHERE roomId IN (:roomIds)")
    fun getRoomSummariesByIdsFlow(roomIds: List<String>): kotlinx.coroutines.flow.Flow<List<RoomListSummaryEntity>>

    @Query("SELECT * FROM room_list_summary")
    suspend fun getAll(): List<RoomListSummaryEntity>
}

