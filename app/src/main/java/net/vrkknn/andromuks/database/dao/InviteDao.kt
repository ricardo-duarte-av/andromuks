package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.InviteEntity

@Dao
interface InviteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(invite: InviteEntity)
    
    @Query("SELECT * FROM invites")
    suspend fun getAllInvites(): List<InviteEntity>
    
    @Query("DELETE FROM invites WHERE roomId = :roomId")
    suspend fun deleteInvite(roomId: String)
    
    @Query("DELETE FROM invites")
    suspend fun deleteAllInvites()
}

