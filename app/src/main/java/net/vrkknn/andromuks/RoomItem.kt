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
    val messageSender: String?,
    val unreadCount: Int?,
    val highlightCount: Int?,
    val avatarUrl: String?,
    val sortingTimestamp: Long? = null,
    val isDirectMessage: Boolean = false,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false
)

@Immutable
data class SpaceItem(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val rooms: List<RoomItem>
)

@Immutable
data class RoomSection(
    val type: RoomSectionType,
    val rooms: List<RoomItem>,
    val spaces: List<SpaceItem> = emptyList(),
    val bridges: List<BridgeItem> = emptyList(),
    val unreadCount: Int = 0
)

@Immutable
data class BridgeItem(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val protocol: String,
    val externalUrl: String?,
    val rooms: List<RoomItem>
)

@Immutable
data class BridgeInfo(
    val bridgebot: String,
    val channel: BridgeChannel,
    val creator: String,
    val protocol: BridgeProtocol
)

@Immutable
data class BridgeChannel(
    val avatarUrl: String?,
    val displayname: String,
    val id: String
)

@Immutable
data class BridgeProtocol(
    val avatarUrl: String?,
    val displayname: String,
    val externalUrl: String?,
    val id: String
)

enum class RoomSectionType {
    HOME,
    SPACES,
    DIRECT_CHATS,
    UNREAD,
    FAVOURITES,
    BRIDGES
}

@Immutable
data class SyncUpdateResult(
    val updatedRooms: List<RoomItem>,
    val newRooms: List<RoomItem>,
    val removedRoomIds: List<String>
)

@Immutable
data class RoomState(
    val roomId: String,
    val name: String?,
    val canonicalAlias: String?,
    val topic: String?,
    val avatarUrl: String?,
    val isEncrypted: Boolean = false,
    val powerLevels: PowerLevelsInfo? = null,
    val pinnedEventIds: List<String> = emptyList()
)

/** Power levels information for a room */
@Immutable
data class PowerLevelsInfo(
    val users: Map<String, Int>,
    val usersDefault: Int,
    val redact: Int
)

@Immutable
data class RoomAnimationState(
    val roomId: String,
    val lastUpdateTime: Long,
    val isAnimating: Boolean = false,
    val previousPosition: Int? = null,
    val currentPosition: Int? = null
)