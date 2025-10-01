package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import org.json.JSONObject

/**
 * Database entity for Matrix timeline events
 * 
 * This entity stores all timeline events with support for:
 * - Redaction handling (isRedacted, redactedBy, redactedAt)
 * - Event relationships (replies, reactions)
 * - Encryption support (decrypted content)
 * - Room-based partitioning for efficient queries
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["roomId", "timestamp"]),
        Index(value = ["eventId"], unique = true),
        Index(value = ["sender"]),
        Index(value = ["type"]),
        Index(value = ["timestamp"]),
        Index(value = ["roomId", "type", "timestamp"])
    ]
)
data class EventEntity(
    @PrimaryKey
    val eventId: String,
    
    val roomId: String,
    val sender: String,
    val type: String,
    val timestamp: Long,
    
    // Content fields
    val content: String?, // JSON string
    val stateKey: String?,
    val decrypted: String?, // JSON string for decrypted content
    val decryptedType: String?,
    val unsigned: String?, // JSON string
    
    // Redaction support
    val isRedacted: Boolean = false,
    val redactedBy: String? = null,
    val redactedAt: Long? = null,
    
    // Server-side IDs for sync
    val rowid: Long = 0,
    val timelineRowid: Long = 0,
    
    // Local metadata
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Convert to TimelineEvent for UI consumption
     */
    fun toTimelineEvent(): net.vrkknn.andromuks.TimelineEvent {
        return net.vrkknn.andromuks.TimelineEvent(
            rowid = this.rowid,
            timelineRowid = this.timelineRowid,
            roomId = this.roomId,
            eventId = this.eventId,
            sender = this.sender,
            type = this.type,
            timestamp = this.timestamp,
            content = this.content?.let { JSONObject(it) },
            stateKey = this.stateKey,
            decrypted = this.decrypted?.let { JSONObject(it) },
            decryptedType = this.decryptedType,
            unsigned = this.unsigned?.let { JSONObject(it) },
            redactedBy = this.redactedBy
        )
    }
    
    companion object {
        /**
         * Create from TimelineEvent
         */
        fun fromTimelineEvent(event: net.vrkknn.andromuks.TimelineEvent): EventEntity {
            return EventEntity(
                eventId = event.eventId,
                roomId = event.roomId,
                sender = event.sender,
                type = event.type,
                timestamp = event.timestamp,
                content = event.content?.toString(),
                stateKey = event.stateKey,
                decrypted = event.decrypted?.toString(),
                decryptedType = event.decryptedType,
                unsigned = event.unsigned?.toString(),
                isRedacted = false, // Will be updated by redaction events
                redactedBy = event.redactedBy,
                redactedAt = null,
                rowid = event.rowid,
                timelineRowid = event.timelineRowid
            )
        }
    }
}
