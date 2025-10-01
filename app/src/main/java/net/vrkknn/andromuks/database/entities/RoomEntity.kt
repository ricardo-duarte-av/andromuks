package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import net.vrkknn.andromuks.RoomItem

/**
 * Database entity for Matrix rooms
 * 
 * Stores room metadata, state, and sync information
 */
@Entity(
    tableName = "rooms",
    indices = [
        Index(value = ["sortingTimestamp"]),
        Index(value = ["unreadCount"]),
        Index(value = ["isDirectMessage"]),
        Index(value = ["lastEventTimestamp"])
    ]
)
data class RoomEntity(
    @PrimaryKey
    val roomId: String,
    
    // Room metadata
    val name: String,
    val canonicalAlias: String?,
    val topic: String?,
    val avatarUrl: String?,
    val isDirectMessage: Boolean = false,
    
    // Message preview
    val messagePreview: String?,
    val messageSender: String?,
    val lastEventTimestamp: Long?,
    
    // Unread counts
    val unreadCount: Int = 0,
    val highlightCount: Int = 0,
    
    // Sync state
    val lastReceivedId: Long = 0,
    val lastSyncTimestamp: Long = 0,
    val isActive: Boolean = true,
    
    // Local metadata
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Convert to RoomItem for UI consumption
     */
    fun toRoomItem(): RoomItem {
        return RoomItem(
            id = this.roomId,
            name = this.name,
            messagePreview = this.messagePreview,
            messageSender = this.messageSender,
            unreadCount = this.unreadCount,
            highlightCount = this.highlightCount,
            avatarUrl = this.avatarUrl,
            sortingTimestamp = this.lastEventTimestamp,
            isDirectMessage = this.isDirectMessage
        )
    }
    
    companion object {
        /**
         * Create from RoomItem
         */
        fun fromRoomItem(room: RoomItem): RoomEntity {
            return RoomEntity(
                roomId = room.id,
                name = room.name,
                canonicalAlias = null,
                topic = null,
                avatarUrl = room.avatarUrl,
                isDirectMessage = room.isDirectMessage,
                messagePreview = room.messagePreview,
                messageSender = room.messageSender,
                lastEventTimestamp = room.sortingTimestamp,
                unreadCount = room.unreadCount ?: 0,
                highlightCount = room.highlightCount ?: 0
            )
        }
    }
}
