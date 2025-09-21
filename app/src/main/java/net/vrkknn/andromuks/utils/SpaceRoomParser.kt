package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.SpaceItem
import org.json.JSONObject

object SpaceRoomParser {
    /**
     * Parses the sync JSON and returns a list of SpaceItem, each with its RoomItem children.
     * Only top-level spaces and their direct room children are included (subspaces ignored).
     */
    fun parseSpacesAndRooms(syncJson: JSONObject): List<SpaceItem> {
        val data = syncJson.optJSONObject("data") ?: return emptyList()
        val roomsJson = data.optJSONObject("rooms") ?: return emptyList()
        val topLevelSpaces = data.optJSONArray("top_level_spaces") ?: return emptyList()
        val spaceEdges = data.optJSONObject("space_edges") ?: JSONObject()

        // Build a map of all rooms (including spaces)
        val allRooms = mutableMapOf<String, RoomItem>()
        val allSpaces = mutableMapOf<String, SpaceItem>()

        // First, collect all rooms and spaces
        val roomKeys = roomsJson.keys()
        while (roomKeys.hasNext()) {
            val roomId = roomKeys.next()
            val roomObj = roomsJson.optJSONObject(roomId) ?: continue
            val meta = roomObj.optJSONObject("meta") ?: continue
            val name = meta.optString("name", roomId)
            val avatar = meta.optString("avatar", null)
            val type = meta.optJSONObject("creation_content")?.optString("type", null)
            if (type == "m.space") {
                // Space, will be constructed below
                continue
            } else {
                // Regular room
                allRooms[roomId] = RoomItem(
                    id = roomId,
                    name = name,
                    avatarUrl = avatar
                )
            }
        }

        // Now, build SpaceItems for top-level spaces
        val result = mutableListOf<SpaceItem>()
        for (i in 0 until topLevelSpaces.length()) {
            val spaceId = topLevelSpaces.optString(i)
            val spaceObj = roomsJson.optJSONObject(spaceId) ?: continue
            val meta = spaceObj.optJSONObject("meta") ?: continue
            val name = meta.optString("name", spaceId)
            val avatar = meta.optString("avatar", null)
            // Get children from space_edges
            val children = mutableListOf<RoomItem>()
            val edgeArr = spaceEdges.optJSONArray(spaceId) ?: continue
            for (j in 0 until edgeArr.length()) {
                val child = edgeArr.optJSONObject(j) ?: continue
                val childId = child.optString("child_id", null) ?: continue
                // Only add if it's a room (not a space)
                val childRoom = allRooms[childId]
                if (childRoom != null) {
                    children.add(childRoom)
                }
            }
            result.add(
                SpaceItem(
                    id = spaceId,
                    name = name,
                    avatarUrl = avatar,
                    rooms = children
                )
            )
        }
        return result
    }
}
