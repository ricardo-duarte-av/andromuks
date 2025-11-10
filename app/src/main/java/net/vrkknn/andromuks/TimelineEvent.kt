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
    val redactedBy: String? = null,
    val localContent: JSONObject? = null,
    val relationType: String? = null, // "m.thread" for thread messages
    val relatesTo: String? = null, // Thread root event ID for thread messages
    val aggregatedReactions: JSONObject? = null
) {
    companion object {
        fun fromJson(json: JSONObject): TimelineEvent {
            // BUG FIX #4: Check for both "timestamp" and "origin_server_ts" since DB stores origin_server_ts
            val timestamp = json.optLong("timestamp", 0L).takeIf { it > 0 }
                ?: json.optLong("origin_server_ts", 0L)
            val content = json.optJSONObject("content")
            val aggregatedReactions = json.optJSONObject("reactions") ?: content?.optJSONObject("reactions")
            if (aggregatedReactions != null && content != null && !content.has("reactions")) {
                content.put("reactions", aggregatedReactions)
            }
            
            return TimelineEvent(
                rowid = json.optLong("rowid", 0),
                timelineRowid = json.optLong("timeline_rowid", 0),
                roomId = json.optString("room_id", ""),
                eventId = json.optString("event_id", ""),
                sender = json.optString("sender", ""),
                type = json.optString("type", ""),
                timestamp = timestamp,
                content = content,
                stateKey = json.optString("state_key")?.takeIf { it.isNotBlank() },
                decrypted = json.optJSONObject("decrypted"),
                decryptedType = json.optString("decrypted_type")?.takeIf { it.isNotBlank() },
                unsigned = json.optJSONObject("unsigned"),
                redactedBy = json.optString("redacted_by")?.takeIf { it.isNotBlank() },
                localContent = json.optJSONObject("local_content"),
                relationType = json.optString("relation_type")?.takeIf { it.isNotBlank() },
                relatesTo = json.optString("relates_to")?.takeIf { it.isNotBlank() },
                aggregatedReactions = aggregatedReactions
            )
        }
    }
    
    /**
     * Extract reply information from this event
     * Note: For thread messages, this returns the fallback reply-to event, NOT the thread root
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
    
    /**
     * Check if this message is part of a thread
     */
    fun isThreadMessage(): Boolean {
        return relationType == "m.thread" && relatesTo != null
    }
    
    /**
     * Check if this event is pinned
     * Note: This requires the AppViewModel to check against room state
     */
    
    /**
     * Get thread information from this event
     * Returns ThreadInfo if this is a thread message, null otherwise
     */
    fun getThreadInfo(): ThreadInfo? {
        if (!isThreadMessage()) return null
        
        val messageContent = when {
            type == "m.room.message" -> content
            type == "m.room.encrypted" && decryptedType == "m.room.message" -> decrypted
            else -> null
        } ?: return null
        
        val relatesTo = messageContent.optJSONObject("m.relates_to")
        val threadRootId = relatesTo?.optString("event_id")?.takeIf { it.isNotBlank() }
            ?: this.relatesTo // Fallback to top-level relates_to
        
        val inReplyTo = relatesTo?.optJSONObject("m.in_reply_to")
        val fallbackReplyId = inReplyTo?.optString("event_id")?.takeIf { it.isNotBlank() }
        
        return if (threadRootId != null) {
            ThreadInfo(
                threadRootEventId = threadRootId,
                fallbackReplyToEventId = fallbackReplyId
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
    val roomId: String,
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
    val blurHash: String?,
    val thumbnailUrl: String? = null,
    val thumbnailBlurHash: String? = null,
    val thumbnailWidth: Int? = null,
    val thumbnailHeight: Int? = null,
    val duration: Int? = null, // Video duration in milliseconds
    val thumbnailIsEncrypted: Boolean = false // Whether thumbnail is encrypted (from thumbnail_file vs thumbnail_url)
)

@Immutable
data class MediaMessage(
    val url: String,
    val filename: String,
    val caption: String?,
    val info: MediaInfo,
    val msgType: String // "m.image", "m.video", "m.audio", or "m.file"
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
    val inviteReason: String?,
    val isDirectMessage: Boolean = false
)

@Immutable
data class ReplyInfo(
    val eventId: String,
    val sender: String,
    val body: String,
    val msgType: String = "m.text"
)

@Immutable
data class ThreadInfo(
    val threadRootEventId: String, // The event ID that started the thread
    val fallbackReplyToEventId: String? = null // The specific message being replied to (for clients without thread support)
)