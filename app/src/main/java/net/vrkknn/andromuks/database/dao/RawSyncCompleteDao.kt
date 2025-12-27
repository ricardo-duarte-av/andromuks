package net.vrkknn.andromuks.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import net.vrkknn.andromuks.database.entities.RawSyncCompleteEntity

/**
 * DAO for raw sync_complete message storage.
 * Used to detect gaps in request_id sequence.
 */
@Dao
interface RawSyncCompleteDao {
    /**
     * Insert or replace a raw sync_complete message
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RawSyncCompleteEntity)
    
    /**
     * Get a specific sync_complete by request_id
     */
    @Query("SELECT * FROM raw_sync_complete WHERE requestId = :requestId")
    suspend fun getByRequestId(requestId: Int): RawSyncCompleteEntity?
    
    /**
     * Get all request_ids in ascending order (most negative first)
     */
    @Query("SELECT requestId FROM raw_sync_complete ORDER BY requestId ASC")
    suspend fun getAllRequestIds(): List<Int>
    
    /**
     * Get the minimum (most negative) request_id
     */
    @Query("SELECT MIN(requestId) FROM raw_sync_complete")
    suspend fun getMinRequestId(): Int?
    
    /**
     * Get the maximum (least negative, closest to 0) request_id
     */
    @Query("SELECT MAX(requestId) FROM raw_sync_complete")
    suspend fun getMaxRequestId(): Int?
    
    /**
     * Find gaps in the request_id sequence.
     * Note: This is a placeholder - gap detection is done in Kotlin code
     * (see SyncIngestor.detectGapsInSyncComplete) for better compatibility
     * across SQLite versions.
     * 
     * Example: If we have -5, -4, -2, -1, this will return [-3]
     */
    @Deprecated("Use SyncIngestor.detectGapsInSyncComplete() instead")
    suspend fun findGaps(minId: Int, maxId: Int): List<Int> {
        // This method is kept for API compatibility but not used
        // Gap detection is implemented in SyncIngestor for better SQLite compatibility
        return emptyList()
    }
    
    /**
     * Get count of stored sync_complete messages
     */
    @Query("SELECT COUNT(*) FROM raw_sync_complete")
    suspend fun getCount(): Int
    
    /**
     * Delete old entries to prevent unbounded growth.
     * Keeps only the most recent N entries (by request_id, which are negative, so "most recent" = closest to 0).
     */
    @Query("""
        DELETE FROM raw_sync_complete 
        WHERE requestId NOT IN (
            SELECT requestId FROM raw_sync_complete 
            ORDER BY requestId DESC 
            LIMIT :keepCount
        )
    """)
    suspend fun deleteOldEntries(keepCount: Int)
    
    /**
     * Delete all entries (for testing/cleanup)
     */
    @Query("DELETE FROM raw_sync_complete")
    suspend fun deleteAll()
}

