package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Render-ready room summary for the room list.
 * Populated from the precomputed renderable timeline head and unread calculations.
 */
@Entity(tableName = "room_list_summary")
data class RoomListSummaryEntity(
    @PrimaryKey val roomId: String,
    val displayName: String?,
    val avatarMxc: String?,
    val lastMessageEventId: String?,
    val lastMessageSenderUserId: String?,
    val lastMessagePreview: String?,
    val lastMessageTimestamp: Long?,
    val unreadCount: Int = 0,
    val highlightCount: Int = 0,
    val isLowPriority: Boolean = false
)

