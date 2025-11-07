package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.ReceiptEntity

@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(receipts: List<ReceiptEntity>)

    @Query("DELETE FROM receipts WHERE roomId = :roomId AND userId = :userId")
    suspend fun deleteUserReceiptsInRoom(roomId: String, userId: String)
    
    @Query("DELETE FROM receipts WHERE roomId = :roomId")
    suspend fun deleteForRoom(roomId: String)
    
    @Query("DELETE FROM receipts WHERE eventId NOT IN (SELECT eventId FROM events)")
    suspend fun deleteOrphanedReceipts(): Int
    
    @Query("DELETE FROM receipts")
    suspend fun deleteAll()

    @Query("SELECT * FROM receipts WHERE roomId = :roomId")
    suspend fun getReceiptsForRoom(roomId: String): List<ReceiptEntity>
}


