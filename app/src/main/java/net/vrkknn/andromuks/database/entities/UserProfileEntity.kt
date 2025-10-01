package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import net.vrkknn.andromuks.MemberProfile

/**
 * Database entity for user profiles
 * 
 * Stores user profile information with caching support
 */
@Entity(
    tableName = "user_profiles",
    indices = [
        Index(value = ["userId"], unique = true),
        Index(value = ["displayName"]),
        Index(value = ["lastUpdated"])
    ]
)
data class UserProfileEntity(
    @PrimaryKey
    val userId: String,
    
    val displayName: String?,
    val avatarUrl: String?,
    
    // Cache metadata
    val lastUpdated: Long = System.currentTimeMillis(),
    val isFromCache: Boolean = false,
    
    // Local metadata
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Convert to MemberProfile for UI consumption
     */
    fun toMemberProfile(): MemberProfile {
        return MemberProfile(
            displayName = this.displayName,
            avatarUrl = this.avatarUrl
        )
    }
    
    companion object {
        /**
         * Create from MemberProfile
         */
        fun fromMemberProfile(userId: String, profile: MemberProfile): UserProfileEntity {
            return UserProfileEntity(
                userId = userId,
                displayName = profile.displayName,
                avatarUrl = profile.avatarUrl
            )
        }
    }
}
