package net.vrkknn.andromuks.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.vrkknn.andromuks.database.entities.SyncStateEntity

/**
 * Data Access Object for sync state management
 * 
 * Manages the current sync state for reconnection and delta sync
 */
@Dao
interface SyncStateDao {
    
    // Basic operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncState(syncState: SyncStateEntity)
    
    @Update
    suspend fun updateSyncState(syncState: SyncStateEntity)
    
    @Query("SELECT * FROM sync_state WHERE id = 'current' LIMIT 1")
    suspend fun getCurrentSyncState(): SyncStateEntity?
    
    @Query("SELECT * FROM sync_state WHERE id = 'current' LIMIT 1")
    fun getCurrentSyncStateFlow(): Flow<SyncStateEntity?>
    
    // Sync state updates
    @Query("UPDATE sync_state SET runId = :runId, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateRunId(runId: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE sync_state SET lastReceivedId = :lastReceivedId, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateLastReceivedId(lastReceivedId: Long, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE sync_state SET lastSyncTimestamp = :syncTimestamp, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateLastSyncTimestamp(syncTimestamp: Long, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE sync_state SET lastInitCompleteTimestamp = :timestamp, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateInitCompleteTimestamp(timestamp: Long, updatedAt: Long = System.currentTimeMillis())
    
    // Client state updates
    @Query("UPDATE sync_state SET currentUserId = :userId, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateCurrentUserId(userId: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE sync_state SET deviceId = :deviceId, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateDeviceId(deviceId: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE sync_state SET homeserverUrl = :homeserverUrl, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateHomeserverUrl(homeserverUrl: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE sync_state SET imageAuthToken = :token, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateImageAuthToken(token: String, updatedAt: Long = System.currentTimeMillis())
    
    // Connection state
    @Query("UPDATE sync_state SET isConnected = :isConnected, lastConnectionTimestamp = :timestamp, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateConnectionState(isConnected: Boolean, timestamp: Long = System.currentTimeMillis(), updatedAt: Long = System.currentTimeMillis())
    
    // Bulk state update
    @Query("UPDATE sync_state SET currentUserId = :userId, deviceId = :deviceId, homeserverUrl = :homeserverUrl, imageAuthToken = :imageAuthToken, updatedAt = :updatedAt WHERE id = 'current'")
    suspend fun updateClientState(userId: String, deviceId: String, homeserverUrl: String, imageAuthToken: String, updatedAt: Long = System.currentTimeMillis())
    
    // Reset sync state (for logout)
    @Query("DELETE FROM sync_state")
    suspend fun clearSyncState()
    
    // Get sync parameters for reconnection
    @Query("SELECT runId, lastReceivedId FROM sync_state WHERE id = 'current' LIMIT 1")
    suspend fun getSyncParameters(): Pair<String?, Long>?
}
