package net.vrkknn.andromuks

import androidx.compose.runtime.Immutable
import org.json.JSONObject

@Immutable
data class TimelineEvent(
    val rowid: Long,
    val timelineRowid: Long,
    val roomId: String,
    val eventId: String,
    val sender: String,
    val type: String,
    val timestamp: Long,
    val content: JSONObject?,
    val stateKey: String? = null,
    val decrypted: JSONObject? = null,
    val decryptedType: String? = null,
    val unsigned: JSONObject? = null,
    val redactedBy: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): TimelineEvent {
            return TimelineEvent(
                rowid = json.optLong("rowid", 0),
                timelineRowid = json.optLong("timeline_rowid", 0),
                roomId = json.optString("room_id", ""),
                eventId = json.optString("event_id", ""),
                sender = json.optString("sender", ""),
                type = json.optString("type", ""),
                timestamp = json.optLong("timestamp", 0),
                content = json.optJSONObject("content"),
                stateKey = json.optString("state_key")?.takeIf { it.isNotBlank() },
                decrypted = json.optJSONObject("decrypted"),
                decryptedType = json.optString("decrypted_type")?.takeIf { it.isNotBlank() },
                unsigned = json.optJSONObject("unsigned"),
                redactedBy = json.optString("redacted_by")?.takeIf { it.isNotBlank() }
            )
        }
    }
    
    /**
     * Extract reply information from this event
     */
    fun getReplyInfo(): ReplyInfo? {
        val messageContent = when {
            type == "m.room.message" -> content
            type == "m.room.encrypted" && decryptedType == "m.room.message" -> decrypted
            else -> null
        } ?: return null
        
        val relatesTo = messageContent?.optJSONObject("m.relates_to")
        val inReplyTo = relatesTo?.optJSONObject("m.in_reply_to")
        val repliedToEventId = inReplyTo?.optString("event_id")?.takeIf { it.isNotBlank() }
        
        return if (repliedToEventId != null) {
            ReplyInfo(
                eventId = repliedToEventId,
                sender = sender,
                body = messageContent.optString("body", ""),
                msgType = messageContent.optString("msgtype", "m.text")
            )
        } else {
            null
        }
    }
}

@Immutable
data class MessageReaction(
    val emoji: String,
    val count: Int,
    val users: List<String>
)

@Immutable
data class ReactionEvent(
    val eventId: String,
    val sender: String,
    val emoji: String,
    val relatesToEventId: String,
    val timestamp: Long
)

@Immutable
data class MediaInfo(
    val width: Int,
    val height: Int,
    val size: Long,
    val mimeType: String,
    val blurHash: String?
)

@Immutable
data class MediaMessage(
    val url: String,
    val filename: String,
    val caption: String?,
    val info: MediaInfo,
    val msgType: String // "m.image" or "m.video"
)

@Immutable
data class ReadReceipt(
    val userId: String,
    val eventId: String,
    val timestamp: Long,
    val receiptType: String
)

@Immutable
data class RoomInvite(
    val roomId: String,
    val createdAt: Long,
    val inviterUserId: String,
    val inviterDisplayName: String?,
    val roomName: String?,
    val roomAvatar: String?,
    val roomTopic: String?,
    val roomCanonicalAlias: String?,
    val inviteReason: String?
)

@Immutable
data class ReplyInfo(
    val eventId: String,
    val sender: String,
    val body: String,
    val msgType: String = "m.text"
)