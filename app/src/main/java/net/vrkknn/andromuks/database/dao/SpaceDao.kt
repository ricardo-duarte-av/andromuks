package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.SpaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(space: SpaceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(spaces: List<SpaceEntity>)

    @Query("SELECT * FROM spaces ORDER BY display_order ASC")
    suspend fun getAllSpaces(): List<SpaceEntity>

    @Query("SELECT * FROM spaces ORDER BY display_order ASC")
    fun getAllSpacesFlow(): Flow<List<SpaceEntity>>

    @Query("SELECT * FROM spaces WHERE spaceId = :spaceId")
    suspend fun getSpace(spaceId: String): SpaceEntity?

    @Query("DELETE FROM spaces WHERE spaceId = :spaceId")
    suspend fun deleteSpace(spaceId: String)
    
    @Query("DELETE FROM spaces")
    suspend fun deleteAllSpaces()
}

