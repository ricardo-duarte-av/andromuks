package net.vrkknn.andromuks

import android.util.Log
import org.json.JSONObject
import org.json.JSONException

data class NotificationData(
    val roomId: String,
    val eventId: String?,
    val sender: String,
    val senderDisplayName: String?,
    val roomName: String?,
    val body: String,
    val type: String?,
    val avatarUrl: String?,
    val roomAvatarUrl: String?,
    val timestamp: Long?,
    val unreadCount: Int?,
    val image: String? = null
)

class NotificationDataParser {
    
    companion object {
        private const val TAG = "NotificationDataParser"
        
        /**
         * Parse notification data from FCM payload
         */
        fun parseNotificationData(data: Map<String, String>): NotificationData? {
            return try {
                // Extract basic fields
                val roomId = data["room_id"] ?: return null
                val eventId = data["event_id"]
                val sender = data["sender"] ?: return null
                val body = data["body"] ?: data["text"] ?: ""
                
                // Parse JSON fields if present
                val senderDisplayName = data["sender_display_name"]
                val roomName = data["room_name"]
                val type = data["type"]
                val avatarUrl = data["avatar_url"]
                val roomAvatarUrl = data["room_avatar_url"]
                val image = data["image"]
                
                // Parse timestamp
                val timestamp = data["ts"]?.toLongOrNull()
                
                // Parse unread count
                val unreadCount = data["counts"]?.let { countsJson ->
                    try {
                        val counts = JSONObject(countsJson)
                        counts.optInt("unread")
                    } catch (e: JSONException) {
                        data["counts"]?.toIntOrNull()
                    }
                }
                
                NotificationData(
                    roomId = roomId,
                    eventId = eventId,
                    sender = sender,
                    senderDisplayName = senderDisplayName,
                    roomName = roomName,
                    body = body,
                    type = type,
                    avatarUrl = avatarUrl,
                    roomAvatarUrl = roomAvatarUrl,
                    timestamp = timestamp,
                    unreadCount = unreadCount,
                    image = image
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing notification data", e)
                null
            }
        }
        
        /**
         * Parse notification data from JSON string
         */
        fun parseFromJson(jsonString: String): NotificationData? {
            return try {
                val json = JSONObject(jsonString)
                val data = mutableMapOf<String, String>()
                
                // Convert JSON object to map
                json.keys().forEach { key ->
                    data[key] = json.getString(key)
                }
                
                parseNotificationData(data)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing JSON notification data", e)
                null
            }
        }
        
        /**
         * Create notification title from parsed data
         */
        fun createNotificationTitle(data: NotificationData): String {
            return when {
                data.roomName != null && data.roomName.isNotBlank() -> {
                    // Group chat or room with name
                    data.roomName
                }
                data.senderDisplayName != null && data.senderDisplayName.isNotBlank() -> {
                    // Direct message with display name
                    data.senderDisplayName
                }
                else -> {
                    // Fallback to sender ID
                    data.sender.substringAfterLast(":")
                }
            }
        }
        
        /**
         * Create notification body from parsed data
         */
        fun createNotificationBody(data: NotificationData): String {
            return when (data.type) {
                "m.text" -> data.body
                "m.image" -> "📷 Image"
                "m.video" -> "🎥 Video"
                "m.audio" -> "🎵 Audio"
                "m.file" -> "📎 File"
                "m.location" -> "📍 Location"
                "m.emote" -> "* ${data.body}"
                else -> data.body
            }
        }
        
        /**
         * Check if notification is for a direct message
         */
        fun isDirectMessage(data: NotificationData): Boolean {
            return data.roomName == null || data.roomName.isBlank()
        }
        
        /**
         * Check if notification is for a group/room
         */
        fun isGroupMessage(data: NotificationData): Boolean {
            return !isDirectMessage(data)
        }
    }
}
