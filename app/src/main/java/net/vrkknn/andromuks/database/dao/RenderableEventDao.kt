package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.vrkknn.andromuks.database.entities.RenderableEventEntity

@Dao
interface RenderableEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(events: List<RenderableEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(event: RenderableEventEntity)

    @Query("SELECT * FROM renderable_events WHERE roomId = :roomId ORDER BY timelineRowId ASC LIMIT :limit")
    suspend fun getForRoomAsc(roomId: String, limit: Int): List<RenderableEventEntity>

    @Query("SELECT * FROM renderable_events WHERE roomId = :roomId ORDER BY timelineRowId ASC")
    fun streamForRoomAsc(roomId: String): Flow<List<RenderableEventEntity>>

    @Query("SELECT * FROM renderable_events WHERE roomId = :roomId ORDER BY timelineRowId DESC LIMIT :limit")
    fun streamLatestForRoomDesc(roomId: String, limit: Int): Flow<List<RenderableEventEntity>>

    @Query("SELECT * FROM renderable_events WHERE eventId = :eventId")
    suspend fun getById(eventId: String): RenderableEventEntity?

    @Query("SELECT * FROM renderable_events WHERE eventId IN (:eventIds)")
    suspend fun getByIds(eventIds: List<String>): List<RenderableEventEntity>

    @Query("DELETE FROM renderable_events WHERE roomId = :roomId")
    suspend fun deleteRoom(roomId: String)
}

