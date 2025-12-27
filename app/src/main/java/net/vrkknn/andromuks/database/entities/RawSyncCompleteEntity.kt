package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing raw sync_complete messages to detect gaps in request_id sequence.
 * 
 * Since request_ids are consecutive negative integers (e.g., -1, -2, -3...),
 * we can detect missing messages by checking for gaps in the sequence.
 * 
 * This table stores the complete JSON for each sync_complete message we receive,
 * allowing us to:
 * 1. Detect gaps in the sequence
 * 2. Potentially recover missing messages
 * 3. Have a complete audit trail
 */
@Entity(
    tableName = "raw_sync_complete",
    indices = [Index(value = ["requestId"])]
)
data class RawSyncCompleteEntity(
    @PrimaryKey val requestId: Int, // The request_id from sync_complete (negative integer)
    val json: String // Complete sync_complete JSON as string
)

