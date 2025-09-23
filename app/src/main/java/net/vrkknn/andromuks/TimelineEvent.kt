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
    val decryptedType: String? = null
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
                decryptedType = json.optString("decrypted_type")?.takeIf { it.isNotBlank() }
            )
        }
    }
}
