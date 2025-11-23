package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Temporary storage for rooms that were skipped during batch processing when backgrounded.
 * These rooms will be processed when app becomes visible or on next sync.
 */
@Entity(
    tableName = "pending_rooms",
    indices = [Index(value = ["roomId"], unique = true)]
)
data class PendingRoomEntity(
    @PrimaryKey val roomId: String,
    val roomJson: String, // Full room JSON from sync_complete
    val timestamp: Long = System.currentTimeMillis() // When it was deferred
)

