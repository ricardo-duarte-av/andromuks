package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.UnprocessedEventEntity

@Dao
interface UnprocessedEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: UnprocessedEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<UnprocessedEventEntity>)

    @Query("SELECT * FROM unprocessed_events ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<UnprocessedEventEntity>

    @Query("DELETE FROM unprocessed_events")
    suspend fun clear()
}

