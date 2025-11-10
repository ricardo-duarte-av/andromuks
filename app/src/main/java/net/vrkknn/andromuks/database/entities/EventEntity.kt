package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["roomId", "timelineRowId"]),
        Index(value = ["roomId", "timestamp"]),
        Index(value = ["roomId"])
    ]
)
data class EventEntity(
    @PrimaryKey val eventId: String,
    val roomId: String,
    val timelineRowId: Long,
    val timestamp: Long,
    val type: String,
    val sender: String,
    val decryptedType: String?,
    val relatesToEventId: String?,
    val threadRootEventId: String?,
    val isRedaction: Boolean,
    val rawJson: String,
    val aggregatedReactionsJson: String?
)


