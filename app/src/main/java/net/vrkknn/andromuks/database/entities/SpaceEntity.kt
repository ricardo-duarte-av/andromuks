package net.vrkknn.andromuks.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val spaceId: String,
    val name: String?,
    val avatarUrl: String?,
    @ColumnInfo(name = "display_order") val order: Int = 0, // Order from top_level_spaces array (0 = first, 1 = second, etc.)
    val updatedAt: Long
)

