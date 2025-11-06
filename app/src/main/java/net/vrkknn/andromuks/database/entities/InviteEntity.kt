package net.vrkknn.andromuks.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invites")
data class InviteEntity(
    @PrimaryKey val roomId: String,
    val createdAt: Long,
    val inviterUserId: String,
    val inviterDisplayName: String?,
    val roomName: String?,
    val roomAvatar: String?,
    val roomTopic: String?,
    val roomCanonicalAlias: String?,
    val inviteReason: String?,
    val isDirectMessage: Boolean
)

