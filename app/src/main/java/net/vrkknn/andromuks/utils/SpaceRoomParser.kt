package net.vrkknn.andromuks.utils

import android.util.Log
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.SyncUpdateResult
import org.json.JSONObject

object SpaceRoomParser {
    /**
     * Parses the sync JSON and returns a list of all non-space rooms.
     * Ignores spaces for now and just shows a flat list of rooms.
     * @deprecated Use parseSyncUpdate instead for incremental updates
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
                Log.d("Andromuks", "SpaceRoomParser: Skipping space: $roomId")
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

        Log.d("Andromuks", "SpaceRoomParser: Parsed ${rooms.size} rooms from sync JSON")
        rooms.forEach { room ->
            Log.d("Andromuks", "SpaceRoomParser: Room: ${room.name} (${room.id})")
        }
        return rooms
    }
    
    /**
     * Parses incremental sync updates and returns what changed.
     * Handles room updates, new rooms, and removed rooms.
     */
    fun parseSyncUpdate(syncJson: JSONObject): SyncUpdateResult {
        val data = syncJson.optJSONObject("data") ?: return SyncUpdateResult(emptyList(), emptyList(), emptyList())
        
        val updatedRooms = mutableListOf<RoomItem>()
        val newRooms = mutableListOf<RoomItem>()
        val removedRoomIds = mutableListOf<String>()
        
        // Process updated/new rooms
        val roomsJson = data.optJSONObject("rooms")
        if (roomsJson != null) {
            val roomKeys = roomsJson.keys()
            while (roomKeys.hasNext()) {
                val roomId = roomKeys.next()
                val roomObj = roomsJson.optJSONObject(roomId) ?: continue
                val meta = roomObj.optJSONObject("meta") ?: continue
                
                // Check if this is a space (skip spaces for now)
                val type = meta.optJSONObject("creation_content")?.optString("type")?.takeIf { it.isNotBlank() }
                if (type == "m.space") {
                    Log.d("Andromuks", "SpaceRoomParser: Skipping space: $roomId")
                    continue
                }
                
                // Parse the room
                val room = parseRoomFromJson(roomId, roomObj, meta)
                if (room != null) {
                    // For now, treat all rooms in sync updates as updates (could be new or existing)
                    updatedRooms.add(room)
                }
            }
        }
        
        // Process left rooms
        val leftRooms = data.optJSONArray("left_rooms")
        if (leftRooms != null) {
            for (i in 0 until leftRooms.length()) {
                val roomId = leftRooms.optString(i)
                if (roomId.isNotBlank()) {
                    removedRoomIds.add(roomId)
                }
            }
        }
        
        // Process invited rooms (treat as new rooms for now)
        val invitedRooms = data.optJSONArray("invited_rooms")
        if (invitedRooms != null) {
            for (i in 0 until invitedRooms.length()) {
                val roomId = invitedRooms.optString(i)
                if (roomId.isNotBlank()) {
                    // For invited rooms, we might want to show them differently
                    // For now, we'll just log them
                    Log.d("Andromuks", "SpaceRoomParser: Invited room: $roomId")
                }
            }
        }
        
        Log.d("Andromuks", "SpaceRoomParser: Sync update - updated: ${updatedRooms.size}, removed: ${removedRoomIds.size}")
        return SyncUpdateResult(updatedRooms, newRooms, removedRoomIds)
    }
    
    private fun parseRoomFromJson(roomId: String, roomObj: JSONObject, meta: JSONObject): RoomItem? {
        try {
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
            
            return RoomItem(
                id = roomId,
                name = name,
                messagePreview = messagePreview,
                unreadCount = if (unreadMessages > 0) unreadMessages else null,
                avatarUrl = avatar
            )
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error parsing room $roomId", e)
            return null
        }
    }
}