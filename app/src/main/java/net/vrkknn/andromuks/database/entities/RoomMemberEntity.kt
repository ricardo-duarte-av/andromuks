package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for storing room members.
 * 
 * Stores member information (display name, avatar) for each room.
 * This allows us to show cached member lists immediately when @ is typed,
 * even if the data is slightly out-of-date.
 * 
 * Uses composite primary key (roomId + userId) to ensure one member per room.
 */
@Entity(
    tableName = "room_members",
    primaryKeys = ["roomId", "userId"]
)
data class RoomMemberEntity(
    val roomId: String,
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val lastUpdated: Long = System.currentTimeMillis()
)

