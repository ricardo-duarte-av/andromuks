package net.vrkknn.andromuks.database.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import net.vrkknn.andromuks.database.entities.UserProfileEntity

/**
 * Data Access Object for user profiles
 * 
 * Manages user profile caching and retrieval
 */
@Dao
interface UserProfileDao {
    
    // Basic CRUD operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfileEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<UserProfileEntity>)
    
    @Update
    suspend fun updateProfile(profile: UserProfileEntity)
    
    @Delete
    suspend fun deleteProfile(profile: UserProfileEntity)
    
    @Query("DELETE FROM user_profiles WHERE userId = :userId")
    suspend fun deleteProfileByUserId(userId: String)
    
    // Profile retrieval
    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    suspend fun getProfileByUserId(userId: String): UserProfileEntity?
    
    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    fun getProfileByUserIdFlow(userId: String): Flow<UserProfileEntity?>
    
    @Query("SELECT * FROM user_profiles WHERE userId IN (:userIds)")
    suspend fun getProfilesByUserIds(userIds: List<String>): List<UserProfileEntity>
    
    // Profile updates
    @Query("UPDATE user_profiles SET displayName = :displayName, avatarUrl = :avatarUrl, lastUpdated = :lastUpdated, isFromCache = :isFromCache WHERE userId = :userId")
    suspend fun updateProfileData(userId: String, displayName: String?, avatarUrl: String?, lastUpdated: Long = System.currentTimeMillis(), isFromCache: Boolean = false)
    
    @Query("UPDATE user_profiles SET displayName = :displayName, lastUpdated = :lastUpdated WHERE userId = :userId")
    suspend fun updateDisplayName(userId: String, displayName: String?, lastUpdated: Long = System.currentTimeMillis())
    
    @Query("UPDATE user_profiles SET avatarUrl = :avatarUrl, lastUpdated = :lastUpdated WHERE userId = :userId")
    suspend fun updateAvatarUrl(userId: String, avatarUrl: String?, lastUpdated: Long = System.currentTimeMillis())
    
    // Cache management
    @Query("SELECT * FROM user_profiles WHERE isFromCache = 1 ORDER BY lastUpdated ASC")
    suspend fun getCachedProfiles(): List<UserProfileEntity>
    
    @Query("SELECT * FROM user_profiles WHERE isFromCache = 0 ORDER BY lastUpdated DESC")
    suspend fun getNonCachedProfiles(): List<UserProfileEntity>
    
    @Query("UPDATE user_profiles SET isFromCache = 0, lastUpdated = :lastUpdated WHERE userId = :userId")
    suspend fun markProfileAsNonCached(userId: String, lastUpdated: Long = System.currentTimeMillis())
    
    // Search functionality
    @Query("SELECT * FROM user_profiles WHERE displayName LIKE :searchTerm OR userId LIKE :searchTerm")
    suspend fun searchProfiles(searchTerm: String): List<UserProfileEntity>
    
    @Query("SELECT * FROM user_profiles WHERE displayName LIKE :searchTerm ORDER BY displayName ASC")
    suspend fun searchProfilesByDisplayName(searchTerm: String): List<UserProfileEntity>
    
    // Statistics
    @Query("SELECT COUNT(*) FROM user_profiles")
    suspend fun getProfileCount(): Int
    
    @Query("SELECT COUNT(*) FROM user_profiles WHERE isFromCache = 1")
    suspend fun getCachedProfileCount(): Int
    
    // Cleanup operations
    @Query("DELETE FROM user_profiles WHERE isFromCache = 1 AND lastUpdated < :cutoffTimestamp")
    suspend fun deleteOldCachedProfiles(cutoffTimestamp: Long)
    
    @Query("DELETE FROM user_profiles WHERE lastUpdated < :cutoffTimestamp")
    suspend fun deleteOldProfiles(cutoffTimestamp: Long)
}
