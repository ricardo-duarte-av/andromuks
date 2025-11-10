package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "reactions",
    primaryKeys = ["roomId", "targetEventId", "key", "sender"],
    indices = [
        Index(value = ["roomId", "targetEventId"]),
        Index(value = ["roomId", "key"]),
        Index(value = ["eventId"])
    ]
)
data class ReactionEntity(
    val roomId: String,
    val targetEventId: String,
    val key: String,
    val sender: String,
    val eventId: String,
    val timestamp: Long
)


