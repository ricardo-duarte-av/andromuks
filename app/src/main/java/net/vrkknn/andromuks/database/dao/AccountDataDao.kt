package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.AccountDataEntity

@Dao
interface AccountDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(accountData: AccountDataEntity)
    
    @Query("SELECT accountDataJson FROM account_data WHERE key = 'account_data' LIMIT 1")
    suspend fun getAccountData(): String?
    
    @Query("DELETE FROM account_data")
    suspend fun deleteAll()
}

