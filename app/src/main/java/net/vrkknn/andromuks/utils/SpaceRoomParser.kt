package net.vrkknn.andromuks.utils

import android.util.Log
import net.vrkknn.andromuks.RoomItem
import org.json.JSONObject

object SpaceRoomParser {
    /**
     * Parses the sync JSON and returns a list of all non-space rooms.
     * Ignores spaces for now and just shows a flat list of rooms.
     */
    fun parseRooms(syncJson: JSONObject): List<RoomItem> {
        val data = syncJson.optJSONObject("data") ?: return emptyList()
        val roomsJson = data.optJSONObject("rooms") ?: return emptyList()

        val rooms = mutableListOf<RoomItem>()

        // Iterate through all rooms
        val roomKeys = roomsJson.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomObj = roomsJson.optJSONObject(roomId) ?: continue
            val meta = roomObj.optJSONObject("meta") ?: continue
            
            // Check if this is a space (skip spaces for now)
            val type = meta.optJSONObject("creation_content")?.optString("type")?.takeIf { it.isNotBlank() }
            if (type == "m.space") {
                Log.d("SpaceRoomParser", "Skipping space: $roomId")
                continue
            }

            // This is a regular room
            val name = meta.optString("name")?.takeIf { it.isNotBlank() } ?: roomId
            val avatar = meta.optString("avatar")?.takeIf { it.isNotBlank() }
            
            // Extract unread count from meta
            val unreadMessages = meta.optInt("unread_messages", 0)
            
            // Extract last message preview from events if available
            val events = roomObj.optJSONArray("events")
            var messagePreview: String? = null
            if (events != null && events.length() > 0) {
                // Get the last event (most recent)
                val lastEvent = events.optJSONObject(events.length() - 1)
                if (lastEvent != null && lastEvent.optString("type") == "m.room.message") {
                    val content = lastEvent.optJSONObject("content")
                    messagePreview = content?.optString("body")?.takeIf { it.isNotBlank() }
                }
            }

            rooms.add(
                RoomItem(
                    id = roomId,
                    name = name,
                    messagePreview = messagePreview,
                    unreadCount = if (unreadMessages > 0) unreadMessages else null,
                    avatarUrl = avatar
                )
            )
        }

        Log.d("SpaceRoomParser", "Parsed ${rooms.size} rooms from sync JSON")
        rooms.forEach { room ->
            Log.d("SpaceRoomParser", "Room: ${room.name} (${room.id})")
        }
        return rooms
    }
}