package net.vrkknn.andromuks

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Immutable
import java.lang.reflect.Modifier

// roomId: String
// name: String
// lastMessagePreview: String?
// unreadCount: Int
// avatarUrl: String?
// isInvite: Boolean


@Immutable
data class RoomItem(
    val id: String,
    val name: String,
    val messagePreview: String?,
    val unreadCount: Int?,
    val avatarUrl: String?,
    val sortingTimestamp: Long? = null
)

@Immutable
data class SpaceItem(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val rooms: List<RoomItem>
)

@Immutable
data class SyncUpdateResult(
    val updatedRooms: List<RoomItem>,
    val newRooms: List<RoomItem>,
    val removedRoomIds: List<String>
)