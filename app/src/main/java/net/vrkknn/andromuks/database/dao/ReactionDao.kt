package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.ReactionEntity

@Dao
interface ReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(reactions: List<ReactionEntity>)

    @Query("DELETE FROM reactions WHERE roomId = :roomId")
    suspend fun clearRoom(roomId: String)

    @Query("DELETE FROM reactions")
    suspend fun clearAll()

    @Query("SELECT * FROM reactions WHERE roomId = :roomId")
    suspend fun getReactionsForRoom(roomId: String): List<ReactionEntity>

    @Query("DELETE FROM reactions WHERE eventId IN (:eventIds)")
    suspend fun deleteByEventIds(eventIds: List<String>)
}


