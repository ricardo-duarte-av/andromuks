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
            
            // Extract last message preview and sender from events if available
            val events = roomObj.optJSONArray("events")
            var messagePreview: String? = null
            var messageSender: String? = null
            if (events != null && events.length() > 0) {
                // Look through all events to find the last actual message
                // Skip non-message events like typing, member changes, state events, etc.
                for (i in events.length() - 1 downTo 0) {
                    val event = events.optJSONObject(i)
                    if (event != null) {
                        val eventType = event.optString("type")
                        when (eventType) {
                            "m.room.message" -> {
                                val content = event.optJSONObject("content")
                                val body = content?.optString("body")?.takeIf { it.isNotBlank() }
                                if (body != null) {
                                    messagePreview = body
                                    // Extract sender user ID (we'll use the local part for now)
                                    val sender = event.optString("sender")?.takeIf { it.isNotBlank() }
                                    messageSender = sender?.substringAfterLast(":") ?: sender
                                    break // Found the last message, stop looking
                                }
                            }
                            "m.room.encrypted" -> {
                                // Check if it's a decrypted message
                                val decryptedType = event.optString("decrypted_type")
                                if (decryptedType == "m.room.message") {
                                    val decrypted = event.optJSONObject("decrypted")
                                    val body = decrypted?.optString("body")?.takeIf { it.isNotBlank() }
                                    if (body != null) {
                                        messagePreview = body
                                        // Extract sender user ID (we'll use the local part for now)
                                        val sender = event.optString("sender")?.takeIf { it.isNotBlank() }
                                        messageSender = sender?.substringAfterLast(":") ?: sender
                                        break // Found the last message, stop looking
                                    }
                                }
                            }
                            // Skip other event types like:
                            // - "typing" (typing indicators)
                            // - "m.room.member" (joins/leaves)
                            // - "m.room.name" (room name changes)
                            // - "m.room.topic" (room topic changes)
                            // - "m.room.avatar" (room avatar changes)
                            // - state events
                            // - etc.
                        }
                    }
                }
            }

            rooms.add(
                RoomItem(
                    id = roomId,
                    name = name,
                    messagePreview = messagePreview,
                    messageSender = messageSender,
                    unreadCount = if (unreadMessages > 0) unreadMessages else null,
                    avatarUrl = avatar
                )
            )
        }

        Log.d("Andromuks", "SpaceRoomParser: Parsed ${rooms.size} rooms from sync JSON")
        return rooms
    }
    
    /**
     * Parses incremental sync updates and returns what changed.
     * Handles room updates, new rooms, and removed rooms.
     */
    fun parseSyncUpdate(syncJson: JSONObject, memberCache: Map<String, Map<String, net.vrkknn.andromuks.MemberProfile>>? = null, appViewModel: net.vrkknn.andromuks.AppViewModel? = null): SyncUpdateResult {
        val data = syncJson.optJSONObject("data") ?: return SyncUpdateResult(emptyList(), emptyList(), emptyList())
        
        // Parse spaces from sync data (only if top_level_spaces is present)
        val topLevelSpaces = data.optJSONArray("top_level_spaces")
        if (topLevelSpaces != null) {
            val spaces = parseSpacesFromSync(data)
            android.util.Log.d("Andromuks", "SpaceRoomParser: Parsed ${spaces.size} spaces from sync data")
            appViewModel?.updateAllSpaces(spaces)
        } else {
            android.util.Log.d("Andromuks", "SpaceRoomParser: No top_level_spaces in this sync, keeping existing spaces")
            // Even if no top_level_spaces, try to update space edges for existing spaces
            val spaceEdges = data.optJSONObject("space_edges")
            if (spaceEdges != null) {
                android.util.Log.d("Andromuks", "SpaceRoomParser: Found space_edges, updating existing spaces")
                updateExistingSpacesWithEdges(spaceEdges, data, appViewModel)
            }
        }
        
        // Debug: Log member cache contents
        Log.d("Andromuks", "SpaceRoomParser: Member cache has ${memberCache?.size ?: 0} rooms")
        
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
                val room = parseRoomFromJson(roomId, roomObj, meta, memberCache, appViewModel)
                if (room != null) {
                    // Only include rooms that have meaningful message content
                    if (room.messagePreview != null && room.messagePreview.isNotBlank()) {
                        updatedRooms.add(room)
                        Log.d("Andromuks", "SpaceRoomParser: Including room with message: ${room.name}")
                    } else {
                        Log.d("Andromuks", "SpaceRoomParser: Discarding room without message content: ${room.name} (ID: ${room.id})")
                    }
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
        
        // Debug: Log the entire sync JSON if we have rooms with null messageSender
        val roomsWithNullSender = updatedRooms.filter { it.messageSender == null }
        if (roomsWithNullSender.isNotEmpty()) {
            Log.w("Andromuks", "SpaceRoomParser: Found ${roomsWithNullSender.size} rooms with null messageSender")
            roomsWithNullSender.forEach { room ->
                Log.w("Andromuks", "SpaceRoomParser: Room with null sender - ID: ${room.id}, Name: ${room.name}, Preview: '${room.messagePreview}'")
            }
            Log.w("Andromuks", "SpaceRoomParser: FULL SYNC JSON that caused the issue:")
            Log.w("Andromuks", "SpaceRoomParser: ${syncJson.toString()}")
        }
        
        Log.d("Andromuks", "SpaceRoomParser: Sync update - updated: ${updatedRooms.size}, removed: ${removedRoomIds.size}")
        return SyncUpdateResult(updatedRooms, newRooms, removedRoomIds)
    }
    
    private fun parseRoomFromJson(roomId: String, roomObj: JSONObject, meta: JSONObject, memberCache: Map<String, Map<String, net.vrkknn.andromuks.MemberProfile>>? = null, appViewModel: net.vrkknn.andromuks.AppViewModel? = null): RoomItem? {
        try {
            // This is a regular room
            val name = meta.optString("name")?.takeIf { it.isNotBlank() } ?: roomId
            val avatar = meta.optString("avatar")?.takeIf { it.isNotBlank() }
            
            // Extract unread count from meta
            val unreadMessages = meta.optInt("unread_messages", 0)
            
            // Detect if this is a Direct Message room
            val isDirectMessage = detectDirectMessage(roomId, roomObj, meta)
            
            // Extract last message preview and sender from events if available
            val events = roomObj.optJSONArray("events")
            var messagePreview: String? = null
            var messageSender: String? = null
            if (events != null && events.length() > 0) {
                // Look through all events to find the last actual message
                // Skip non-message events like typing, member changes, state events, etc.
                for (i in events.length() - 1 downTo 0) {
                    val event = events.optJSONObject(i)
                    if (event != null) {
                        val eventType = event.optString("type")
                        when (eventType) {
                            "m.room.message" -> {
                                val content = event.optJSONObject("content")
                                val body = content?.optString("body")?.takeIf { it.isNotBlank() }
                                if (body != null) {
                                    messagePreview = body
                                    // Extract sender - this should ALWAYS be available in Matrix events
                                    val sender = event.optString("sender")
                                    Log.d("Andromuks", "SpaceRoomParser: Processing m.room.message event for room $roomId")
                                    Log.d("Andromuks", "SpaceRoomParser: Event JSON: ${event.toString()}")
                                    if (sender.isNotBlank()) {
                                        // Try to get display name from member cache using full Matrix ID
                                        val roomMembers = memberCache?.get(roomId)
                                        val memberProfile = roomMembers?.get(sender)
                                        Log.d("Andromuks", "SpaceRoomParser: Looking up sender '$sender' in room '$roomId', found profile: $memberProfile")
                                        messageSender = if (memberProfile?.displayName != null && memberProfile.displayName.isNotBlank()) {
                                            Log.d("Andromuks", "SpaceRoomParser: Using display name: ${memberProfile.displayName}")
                                            memberProfile.displayName
                                        } else {
                                            // No display name found, request it from server
                                            Log.d("Andromuks", "SpaceRoomParser: No display name for $sender, requesting profile")
                                            appViewModel?.requestUserProfile(sender)
                                            // For now, use Matrix ID until profile is fetched
                                            sender
                                        }
                                    } else {
                                        Log.w("Andromuks", "SpaceRoomParser: WARNING - No sender found in message event!")
                                        Log.w("Andromuks", "SpaceRoomParser: Event that caused the issue: ${event.toString()}")
                                        messageSender = "Unknown"
                                    }
                                    break // Found the last message, stop looking
                                }
                            }
                            "m.room.encrypted" -> {
                                // Check if it's a decrypted message
                                val decryptedType = event.optString("decrypted_type")
                                if (decryptedType == "m.room.message") {
                                    val decrypted = event.optJSONObject("decrypted")
                                    val body = decrypted?.optString("body")?.takeIf { it.isNotBlank() }
                                    if (body != null) {
                                        messagePreview = body
                                        // Extract sender - this should ALWAYS be available in Matrix events
                                        val sender = event.optString("sender")
                                        Log.d("Andromuks", "SpaceRoomParser: Processing encrypted m.room.message event for room $roomId")
                                        Log.d("Andromuks", "SpaceRoomParser: Encrypted event JSON: ${event.toString()}")
                                        if (sender.isNotBlank()) {
                                            // Try to get display name from member cache using full Matrix ID
                                            val roomMembers = memberCache?.get(roomId)
                                            val memberProfile = roomMembers?.get(sender)
                                            Log.d("Andromuks", "SpaceRoomParser: Looking up encrypted sender '$sender' in room '$roomId', found profile: $memberProfile")
                                            messageSender = if (memberProfile?.displayName != null && memberProfile.displayName.isNotBlank()) {
                                                Log.d("Andromuks", "SpaceRoomParser: Using display name: ${memberProfile.displayName}")
                                                memberProfile.displayName
                                            } else {
                                                // No display name found, request it from server
                                                Log.d("Andromuks", "SpaceRoomParser: No display name for $sender, requesting profile")
                                                appViewModel?.requestUserProfile(sender)
                                                // For now, use Matrix ID until profile is fetched
                                                sender
                                            }
                                        } else {
                                            Log.w("Andromuks", "SpaceRoomParser: WARNING - No sender found in encrypted message event!")
                                            Log.w("Andromuks", "SpaceRoomParser: Encrypted event that caused the issue: ${event.toString()}")
                                            messageSender = "Unknown"
                                        }
                                        break // Found the last message, stop looking
                                    }
                                }
                            }
                            // Skip other event types like:
                            // - "typing" (typing indicators)
                            // - "m.room.member" (joins/leaves)
                            // - "m.room.name" (room name changes)
                            // - "m.room.topic" (room topic changes)
                            // - "m.room.avatar" (room avatar changes)
                            // - state events
                            // - etc.
                        }
                    }
                }
            }
            
            // Extract sorting_timestamp from meta
            val sortingTimestamp = meta.optLong("sorting_timestamp", 0L).takeIf { it != 0L }
            
            return RoomItem(
                id = roomId,
                name = name,
                messagePreview = messagePreview,
                messageSender = messageSender,
                unreadCount = if (unreadMessages > 0) unreadMessages else null,
                avatarUrl = avatar,
                sortingTimestamp = sortingTimestamp,
                isDirectMessage = isDirectMessage
            )
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error parsing room $roomId", e)
            return null
        }
    }
    
    /**
     * Detects if a room is a Direct Message (DM) based on the dm_user_id field in meta.
     * This is the most reliable and simple method for gomuks JSON.
     */
    private fun detectDirectMessage(roomId: String, roomObj: JSONObject, meta: JSONObject): Boolean {
        try {
            // Check if dm_user_id is populated in meta - this indicates a DM
            val dmUserId = meta.optString("dm_user_id")?.takeIf { it.isNotBlank() }
            
            if (dmUserId != null) {
                Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as DM (dm_user_id: $dmUserId)")
                return true
            } else {
                Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as group room (no dm_user_id)")
                return false
            }
            
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error detecting DM status for room $roomId", e)
            return false
        }
    }
    
    /**
     * Parses spaces from sync data using top_level_spaces array
     */
    private fun parseSpacesFromSync(data: JSONObject): List<net.vrkknn.andromuks.SpaceItem> {
        val spaces = mutableListOf<net.vrkknn.andromuks.SpaceItem>()
        
        try {
            // Get top_level_spaces array from sync data
            val topLevelSpaces = data.optJSONArray("top_level_spaces")
            if (topLevelSpaces != null) {
                Log.d("Andromuks", "SpaceRoomParser: Found top_level_spaces with ${topLevelSpaces.length()} spaces")
                Log.d("Andromuks", "SpaceRoomParser: top_level_spaces content: ${topLevelSpaces.toString()}")
                
                // Parse space_edges to get child rooms for each space
                val spaceEdges = data.optJSONObject("space_edges")
                Log.d("Andromuks", "SpaceRoomParser: space_edges found: ${spaceEdges != null}")
                
                for (i in 0 until topLevelSpaces.length()) {
                    val spaceId = topLevelSpaces.optString(i)
                    if (spaceId.isNotBlank()) {
                        // Try to get space details from rooms data
                        val roomsJson = data.optJSONObject("rooms")
                        val spaceDetails = roomsJson?.optJSONObject(spaceId)
                        val meta = spaceDetails?.optJSONObject("meta")
                        
                        val name = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: spaceId
                        val avatar = meta?.optString("avatar")?.takeIf { it.isNotBlank() }
                        
                        // Get child rooms from space_edges
                        val childRooms = mutableListOf<net.vrkknn.andromuks.RoomItem>()
                        if (spaceEdges != null) {
                            val spaceEdgeArray = spaceEdges.optJSONArray(spaceId)
                            if (spaceEdgeArray != null) {
                                Log.d("Andromuks", "SpaceRoomParser: Space $spaceId has ${spaceEdgeArray.length()} child rooms")
                                for (j in 0 until spaceEdgeArray.length()) {
                                    val edge = spaceEdgeArray.optJSONObject(j)
                                    val childId = edge?.optString("child_id")?.takeIf { it.isNotBlank() }
                                    if (childId != null) {
                                        // Try to find this room in the rooms data
                                        val childRoomData = roomsJson?.optJSONObject(childId)
                                        if (childRoomData != null) {
                                            val childMeta = childRoomData.optJSONObject("meta")
                                            val childName = childMeta?.optString("name")?.takeIf { it.isNotBlank() } ?: childId
                                            val childAvatar = childMeta?.optString("avatar")?.takeIf { it.isNotBlank() }
                                            val unreadCount = childMeta?.optInt("unread_messages", 0) ?: 0
                                            
                                            val childRoom = net.vrkknn.andromuks.RoomItem(
                                                id = childId,
                                                name = childName,
                                                avatarUrl = childAvatar,
                                                unreadCount = if (unreadCount > 0) unreadCount else null,
                                                messagePreview = null,
                                                messageSender = null,
                                                isDirectMessage = false
                                            )
                                            childRooms.add(childRoom)
                                            Log.d("Andromuks", "SpaceRoomParser: Added child room: $childName (unread: $unreadCount)")
                                        }
                                    }
                                }
                            }
                        }
                        
                        val spaceItem = net.vrkknn.andromuks.SpaceItem(
                            id = spaceId,
                            name = name,
                            avatarUrl = avatar,
                            rooms = childRooms
                        )
                        spaces.add(spaceItem)
                        Log.d("Andromuks", "SpaceRoomParser: Found space: $name (ID: $spaceId) with ${childRooms.size} rooms")
                    }
                }
            } else {
                Log.d("Andromuks", "SpaceRoomParser: No top_level_spaces found in sync data")
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error parsing spaces", e)
        }
        
        Log.d("Andromuks", "SpaceRoomParser: Parsed ${spaces.size} spaces")
        return spaces
    }
    
    /**
     * Updates existing spaces with child rooms from space_edges
     */
    private fun updateExistingSpacesWithEdges(spaceEdges: JSONObject, data: JSONObject, appViewModel: net.vrkknn.andromuks.AppViewModel?) {
        try {
            val roomsJson = data.optJSONObject("rooms")
            val updatedSpaces = mutableListOf<net.vrkknn.andromuks.SpaceItem>()
            
            // Get current spaces from AppViewModel
            val currentSpaces = appViewModel?.allSpaces ?: emptyList()
            android.util.Log.d("Andromuks", "SpaceRoomParser: Updating ${currentSpaces.size} existing spaces with edges")
            
            for (space in currentSpaces) {
                val spaceEdgeArray = spaceEdges.optJSONArray(space.id)
                val childRooms = mutableListOf<net.vrkknn.andromuks.RoomItem>()
                
                if (spaceEdgeArray != null) {
                    android.util.Log.d("Andromuks", "SpaceRoomParser: Space ${space.name} has ${spaceEdgeArray.length()} child rooms")
                    for (j in 0 until spaceEdgeArray.length()) {
                        val edge = spaceEdgeArray.optJSONObject(j)
                        val childId = edge?.optString("child_id")?.takeIf { it.isNotBlank() }
                        if (childId != null) {
                            // Try to find this room in the rooms data
                            val childRoomData = roomsJson?.optJSONObject(childId)
                            if (childRoomData != null) {
                                val childMeta = childRoomData.optJSONObject("meta")
                                val childName = childMeta?.optString("name")?.takeIf { it.isNotBlank() } ?: childId
                                val childAvatar = childMeta?.optString("avatar")?.takeIf { it.isNotBlank() }
                                val unreadCount = childMeta?.optInt("unread_messages", 0) ?: 0
                                
                                val childRoom = net.vrkknn.andromuks.RoomItem(
                                    id = childId,
                                    name = childName,
                                    avatarUrl = childAvatar,
                                    unreadCount = if (unreadCount > 0) unreadCount else null,
                                    messagePreview = null,
                                    messageSender = null,
                                    isDirectMessage = false
                                )
                                childRooms.add(childRoom)
                                android.util.Log.d("Andromuks", "SpaceRoomParser: Added child room: $childName (unread: $unreadCount)")
                            }
                        }
                    }
                }
                
                // Create updated space with new child rooms
                val updatedSpace = space.copy(rooms = childRooms)
                updatedSpaces.add(updatedSpace)
                android.util.Log.d("Andromuks", "SpaceRoomParser: Updated space ${space.name} with ${childRooms.size} rooms")
            }
            
            // Update the spaces in AppViewModel
            appViewModel?.updateAllSpaces(updatedSpaces)
            android.util.Log.d("Andromuks", "SpaceRoomParser: Updated ${updatedSpaces.size} spaces with edges")
            
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "SpaceRoomParser: Error updating spaces with edges", e)
        }
    }
}