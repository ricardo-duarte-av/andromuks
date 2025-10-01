package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import net.vrkknn.andromuks.ReactionEvent

/**
 * Database entity for message reactions
 * 
 * Stores individual reaction events and aggregates them for display
 */
@Entity(
    tableName = "reactions",
    indices = [
        Index(value = ["roomId", "relatesToEventId"]),
        Index(value = ["relatesToEventId", "emoji"]),
        Index(value = ["sender"]),
        Index(value = ["timestamp"])
    ]
)
data class ReactionEntity(
    @PrimaryKey
    val reactionId: String, // eventId of the reaction event
    
    val roomId: String,
    val relatesToEventId: String,
    val sender: String,
    val emoji: String,
    val timestamp: Long,
    
    // Local metadata
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Convert to ReactionEvent for UI consumption
     */
    fun toReactionEvent(): ReactionEvent {
        return ReactionEvent(
            eventId = this.reactionId,
            sender = this.sender,
            emoji = this.emoji,
            relatesToEventId = this.relatesToEventId,
            timestamp = this.timestamp
        )
    }
    
    companion object {
        /**
         * Create from ReactionEvent
         */
        fun fromReactionEvent(event: ReactionEvent, roomId: String): ReactionEntity {
            return ReactionEntity(
                reactionId = event.eventId,
                roomId = roomId,
                relatesToEventId = event.relatesToEventId,
                sender = event.sender,
                emoji = event.emoji,
                timestamp = event.timestamp
            )
        }
    }
}
