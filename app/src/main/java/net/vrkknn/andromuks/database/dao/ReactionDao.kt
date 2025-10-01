package net.vrkknn.andromuks.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.vrkknn.andromuks.database.entities.ReactionEntity

/**
 * Data Access Object for message reactions
 * 
 * Manages reaction events and provides aggregated reaction data
 */
@Dao
interface ReactionDao {
    
    // Basic CRUD operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReaction(reaction: ReactionEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReactions(reactions: List<ReactionEntity>)
    
    @Delete
    suspend fun deleteReaction(reaction: ReactionEntity)
    
    @Query("DELETE FROM reactions WHERE reactionId = :reactionId")
    suspend fun deleteReactionById(reactionId: String)
    
    // Reaction retrieval
    @Query("SELECT * FROM reactions WHERE relatesToEventId = :eventId ORDER BY timestamp ASC")
    fun getReactionsForEvent(eventId: String): Flow<List<ReactionEntity>>
    
    @Query("SELECT * FROM reactions WHERE roomId = :roomId AND relatesToEventId = :eventId ORDER BY timestamp ASC")
    suspend fun getReactionsForEventInRoom(roomId: String, eventId: String): List<ReactionEntity>
    
    @Query("SELECT * FROM reactions WHERE roomId = :roomId ORDER BY timestamp DESC")
    fun getReactionsForRoom(roomId: String): Flow<List<ReactionEntity>>
    
    // Aggregated reaction data
    @Query("""
        SELECT emoji, COUNT(*) as count, GROUP_CONCAT(sender) as users 
        FROM reactions 
        WHERE relatesToEventId = :eventId 
        GROUP BY emoji 
        ORDER BY count DESC
    """)
    suspend fun getAggregatedReactionsForEvent(eventId: String): List<AggregatedReaction>
    
    @Query("""
        SELECT emoji, COUNT(*) as count, GROUP_CONCAT(sender) as users 
        FROM reactions 
        WHERE roomId = :roomId AND relatesToEventId = :eventId 
        GROUP BY emoji 
        ORDER BY count DESC
    """)
    suspend fun getAggregatedReactionsForEventInRoom(roomId: String, eventId: String): List<AggregatedReaction>
    
    // User-specific reactions
    @Query("SELECT * FROM reactions WHERE sender = :userId AND relatesToEventId = :eventId")
    suspend fun getUserReactionsForEvent(userId: String, eventId: String): List<ReactionEntity>
    
    @Query("SELECT * FROM reactions WHERE sender = :userId AND roomId = :roomId ORDER BY timestamp DESC")
    fun getUserReactionsForRoom(userId: String, roomId: String): Flow<List<ReactionEntity>>
    
    // Reaction removal
    @Query("DELETE FROM reactions WHERE sender = :userId AND relatesToEventId = :eventId AND emoji = :emoji")
    suspend fun removeUserReaction(userId: String, eventId: String, emoji: String)
    
    @Query("DELETE FROM reactions WHERE relatesToEventId = :eventId")
    suspend fun removeAllReactionsForEvent(eventId: String)
    
    // Statistics
    @Query("SELECT COUNT(*) FROM reactions WHERE relatesToEventId = :eventId")
    suspend fun getReactionCountForEvent(eventId: String): Int
    
    @Query("SELECT COUNT(*) FROM reactions WHERE roomId = :roomId")
    suspend fun getReactionCountForRoom(roomId: String): Int
    
    // Cleanup operations
    @Query("DELETE FROM reactions WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldReactions(cutoffTimestamp: Long)
    
    @Query("DELETE FROM reactions WHERE roomId = :roomId AND timestamp < :cutoffTimestamp")
    suspend fun deleteOldReactionsForRoom(roomId: String, cutoffTimestamp: Long)
}

/**
 * Data class for aggregated reaction results
 */
data class AggregatedReaction(
    val emoji: String,
    val count: Int,
    val users: String // Comma-separated list of user IDs
)
