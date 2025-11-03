package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "receipts",
    primaryKeys = ["userId", "eventId"],
    indices = [
        Index(value = ["roomId", "eventId"]),
        Index(value = ["roomId", "userId"]) 
    ]
)
data class ReceiptEntity(
    val userId: String,
    val eventId: String,
    val roomId: String,
    val timestamp: Long,
    val type: String
)


