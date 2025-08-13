package net.vrkknn.andromuks

import androidx.compose.runtime.Immutable

// roomId: String
// name: String
// lastMessagePreview: String?
// unreadCount: Int
// avatarUrl: String?
// isInvite: Boolean

@Immutable
data class RoomItem(
    val roomId: String,
    val name: String
)