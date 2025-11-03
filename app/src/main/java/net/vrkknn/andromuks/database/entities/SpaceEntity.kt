package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val spaceId: String,
    val name: String?,
    val avatarUrl: String?,
    val updatedAt: Long
)

