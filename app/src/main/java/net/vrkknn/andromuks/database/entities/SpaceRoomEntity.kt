package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "space_rooms",
    primaryKeys = ["spaceId", "childId"],
    indices = [
        Index(value = ["spaceId"]),
        Index(value = ["childId"])
    ]
)
data class SpaceRoomEntity(
    val spaceId: String,
    val childId: String,
    val parentEventRowId: Long?,
    val childEventRowId: Long?,
    val canonical: Boolean,
    val suggested: Boolean,
    val order: String?,
    val updatedAt: Long
)

