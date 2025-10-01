package net.vrkknn.andromuks.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.vrkknn.andromuks.database.entities.RoomEntity

/**
 * Data Access Object for rooms
 * 
 * Provides efficient queries for room management with support for:
 * - Room state updates
 * - Unread count management
 * - Sorting and filtering
 */
@Dao
interface RoomDao {
    
    // Basic CRUD operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoom(room: RoomEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRooms(rooms: List<RoomEntity>)
    
    @Update
    suspend fun updateRoom(room: RoomEntity)
    
    @Delete
    suspend fun deleteRoom(room: RoomEntity)
    
    @Query("DELETE FROM rooms WHERE roomId = :roomId")
    suspend fun deleteRoomById(roomId: String)
    
    // Room retrieval
    @Query("SELECT * FROM rooms WHERE roomId = :roomId LIMIT 1")
    suspend fun getRoomById(roomId: String): RoomEntity?
    
    @Query("SELECT * FROM rooms WHERE roomId = :roomId LIMIT 1")
    fun getRoomByIdFlow(roomId: String): Flow<RoomEntity?>
    
    // Room listing with different sorting options
    @Query("SELECT * FROM rooms WHERE isActive = 1 ORDER BY lastEventTimestamp DESC")
    fun getAllRooms(): Flow<List<RoomEntity>>
    
    @Query("SELECT * FROM rooms WHERE isActive = 1 ORDER BY sortingTimestamp DESC")
    fun getAllRoomsSorted(): Flow<List<RoomEntity>>
    
    @Query("SELECT * FROM rooms WHERE isActive = 1 AND unreadCount > 0 ORDER BY lastEventTimestamp DESC")
    fun getUnreadRooms(): Flow<List<RoomEntity>>
    
    @Query("SELECT * FROM rooms WHERE isActive = 1 AND isDirectMessage = 1 ORDER BY lastEventTimestamp DESC")
    fun getDirectMessageRooms(): Flow<List<RoomEntity>>
    
    // Room state updates
    @Query("UPDATE rooms SET name = :name, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun updateRoomName(roomId: String, name: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE rooms SET avatarUrl = :avatarUrl, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun updateRoomAvatar(roomId: String, avatarUrl: String?, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE rooms SET topic = :topic, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun updateRoomTopic(roomId: String, topic: String?, updatedAt: Long = System.currentTimeMillis())
    
    // Unread count management
    @Query("UPDATE rooms SET unreadCount = :unreadCount, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun updateUnreadCount(roomId: String, unreadCount: Int, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE rooms SET highlightCount = :highlightCount, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun updateHighlightCount(roomId: String, highlightCount: Int, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE rooms SET unreadCount = 0, highlightCount = 0, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun markRoomAsRead(roomId: String, updatedAt: Long = System.currentTimeMillis())
    
    // Message preview updates
    @Query("UPDATE rooms SET messagePreview = :messagePreview, messageSender = :messageSender, lastEventTimestamp = :timestamp, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun updateMessagePreview(roomId: String, messagePreview: String?, messageSender: String?, timestamp: Long, updatedAt: Long = System.currentTimeMillis())
    
    // Sync state management
    @Query("UPDATE rooms SET lastReceivedId = :lastReceivedId, lastSyncTimestamp = :syncTimestamp, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun updateSyncState(roomId: String, lastReceivedId: Long, syncTimestamp: Long, updatedAt: Long = System.currentTimeMillis())
    
    // Statistics
    @Query("SELECT COUNT(*) FROM rooms WHERE isActive = 1")
    suspend fun getActiveRoomCount(): Int
    
    @Query("SELECT COUNT(*) FROM rooms WHERE isActive = 1 AND unreadCount > 0")
    suspend fun getUnreadRoomCount(): Int
    
    @Query("SELECT SUM(unreadCount) FROM rooms WHERE isActive = 1")
    suspend fun getTotalUnreadCount(): Int
    
    // Room activation/deactivation
    @Query("UPDATE rooms SET isActive = 0, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun deactivateRoom(roomId: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE rooms SET isActive = 1, updatedAt = :updatedAt WHERE roomId = :roomId")
    suspend fun activateRoom(roomId: String, updatedAt: Long = System.currentTimeMillis())
    
    // Cleanup operations
    @Query("DELETE FROM rooms WHERE isActive = 0 AND updatedAt < :cutoffTimestamp")
    suspend fun deleteInactiveRooms(cutoffTimestamp: Long)
}
