package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "room_state")
data class RoomStateEntity(
    @PrimaryKey val roomId: String,
    val name: String?,
    val topic: String?,
    val avatarUrl: String?,
    val canonicalAlias: String?,
    val isDirect: Boolean,
    val isFavourite: Boolean,
    val isLowPriority: Boolean,
    val bridgeInfoJson: String?,
    val updatedAt: Long
)


