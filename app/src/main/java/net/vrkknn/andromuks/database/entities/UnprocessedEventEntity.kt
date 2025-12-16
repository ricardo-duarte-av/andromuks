package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Captures events we intentionally could not persist, so we never silently drop data.
 */
@Entity(
    tableName = "unprocessed_events",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["eventId"]),
        Index(value = ["roomId"])
    ]
)
data class UnprocessedEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val roomId: String?,
    val rawJson: String,
    val reason: String,
    val source: String?,
    val createdAt: Long = System.currentTimeMillis()
)

