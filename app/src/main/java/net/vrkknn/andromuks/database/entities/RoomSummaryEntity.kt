package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "room_summary",
    indices = [Index(value = ["lastTimestamp"], orders = [Index.Order.DESC])]
)
data class RoomSummaryEntity(
    @PrimaryKey val roomId: String,
    val lastEventId: String?,
    val lastTimestamp: Long,
    val unreadCount: Int,
    val highlightCount: Int,
    val messageSender: String?,
    val messagePreview: String?
)


