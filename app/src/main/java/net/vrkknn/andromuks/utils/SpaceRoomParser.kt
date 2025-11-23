package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
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
                //Log.d("Andromuks", "SpaceRoomParser: Skipping space: $roomId")
                continue
            }

            // This is a regular room
            val name = meta.optString("name")?.takeIf { it.isNotBlank() } ?: roomId
            val avatar = meta.optString("avatar")?.takeIf { it.isNotBlank() }
            
            // Extract unread count and highlight count from meta
            val unreadMessages = meta.optInt("unread_messages", 0)
            val unreadHighlights = meta.optInt("unread_highlights", 0)
            
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
                                    // Extract sender user ID (use full Matrix ID for profile lookup)
                                    val sender = event.optString("sender")?.takeIf { it.isNotBlank() }
                                    //android.util.Log.d("Andromuks", "SpaceRoomParser: Message event sender: '$sender' for room $roomId")
                                    messageSender = sender
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
                                        // Extract sender user ID (use full Matrix ID for profile lookup)
                                        val sender = event.optString("sender")?.takeIf { it.isNotBlank() }
                                        //android.util.Log.d("Andromuks", "SpaceRoomParser: Encrypted message event sender: '$sender' for room $roomId")
                                        messageSender = sender
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

            // Extract tags from account_data.m.tag
            var isFavourite = false
            var isLowPriority = false
            
            val accountData = roomObj.optJSONObject("account_data")
            if (accountData != null) {
                val tagData = accountData.optJSONObject("m.tag")
                if (tagData != null) {
                    val content = tagData.optJSONObject("content")
                    if (content != null) {
                        val tags = content.optJSONObject("tags")
                        if (tags != null) {
                            if (tags.has("m.favourite")) {
                                isFavourite = true
                            }
                            if (tags.has("m.lowpriority")) {
                                isLowPriority = true
                            }
                        }
                    }
                }
            }
            
            //android.util.Log.d("Andromuks", "SpaceRoomParser: Creating RoomItem for '$name' - messagePreview='$messagePreview', messageSender='$messageSender'")
            rooms.add(
                RoomItem(
                    id = roomId,
                    name = name,
                    messagePreview = messagePreview,
                    messageSender = messageSender,
                    unreadCount = if (unreadMessages > 0) unreadMessages else null,
                    highlightCount = if (unreadHighlights > 0) unreadHighlights else null,
                    avatarUrl = avatar,
                    isFavourite = isFavourite,
                    isLowPriority = isLowPriority
                )
            )
        }

        //Log.d("Andromuks", "SpaceRoomParser: Parsed ${rooms.size} rooms from sync JSON")
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
            // Only parse basic space info, don't populate edges yet
            val spaces = parseSpacesBasic(data)
            //android.util.Log.d("Andromuks", "SpaceRoomParser: Parsed ${spaces.size} spaces from sync data (basic info only)")
            appViewModel?.updateAllSpaces(spaces)
            
            // Store space edges for later processing after init_complete
            val spaceEdges = data.optJSONObject("space_edges")
            if (spaceEdges != null) {
                //android.util.Log.d("Andromuks", "SpaceRoomParser: Storing space_edges for later processing")
                appViewModel?.storeSpaceEdges(spaceEdges)
            }
        } //else {
            //android.util.Log.d("Andromuks", "SpaceRoomParser: No top_level_spaces in this sync, keeping existing spaces")
            // Don't update space edges here - they will be populated after init_complete
        //}
        
        // Debug: Log member cache contents
        //Log.d("Andromuks", "SpaceRoomParser: Member cache has ${memberCache?.size ?: 0} rooms")
        
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
                    //Log.d("Andromuks", "SpaceRoomParser: Skipping space: $roomId")
                    continue
                }
                
                // Parse the room
                val room = parseRoomFromJson(roomId, roomObj, meta, memberCache, appViewModel)
                if (room != null) {
                    // Include all rooms, regardless of message content
                    updatedRooms.add(room)
                    //if (room.messagePreview != null && room.messagePreview.isNotBlank()) {
                        //Log.d("Andromuks", "SpaceRoomParser: Including room with message: ${room.name}")
                    //} else {
                        //Log.d("Andromuks", "SpaceRoomParser: Including room without message content: ${room.name} (ID: ${room.id})")
                    //}
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
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "SpaceRoomParser: Invited room: $roomId")
                }
            }
        }
        
        // Debug: Log rooms with null messageSender (this is normal for receipt-only syncs)
        val roomsWithNullSender = updatedRooms.filter { it.messageSender == null }
        //if (roomsWithNullSender.isNotEmpty()) {
        //    Log.d("Andromuks", "SpaceRoomParser: ${roomsWithNullSender.size} rooms in sync with no new messages (may have receipts/state updates only)")
        //    roomsWithNullSender.forEach { room ->
        //        Log.d("Andromuks", "SpaceRoomParser: Room without new message - ID: ${room.id}, Name: ${room.name}")
        //    }
        //}
        
        //Log.d("Andromuks", "SpaceRoomParser: Sync update - updated: ${updatedRooms.size}, removed: ${removedRoomIds.size}")
        return SyncUpdateResult(updatedRooms, newRooms, removedRoomIds)
    }
    
    private fun parseRoomFromJson(roomId: String, roomObj: JSONObject, meta: JSONObject, memberCache: Map<String, Map<String, net.vrkknn.andromuks.MemberProfile>>? = null, appViewModel: net.vrkknn.andromuks.AppViewModel? = null): RoomItem? {
        try {
            // This is a regular room
            val name = meta.optString("name")?.takeIf { it.isNotBlank() } ?: roomId
            val avatar = meta.optString("avatar")?.takeIf { it.isNotBlank() }
            
            // Extract unread count and highlight count from meta
            val unreadMessages = meta.optInt("unread_messages", 0)
            val unreadHighlights = meta.optInt("unread_highlights", 0)
            
            // Detect if this is a Direct Message room
            val isDirectMessage = detectDirectMessage(roomId, roomObj, meta, appViewModel)
            
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
                                    //Log.d("Andromuks", "SpaceRoomParser: Processing m.room.message event for room $roomId")
                                    //Log.d("Andromuks", "SpaceRoomParser: Event JSON: ${event.toString()}")
                                    if (sender.isNotBlank()) {
                                        // Try to get display name from member cache using full Matrix ID
                                        val roomMembers = memberCache?.get(roomId)
                                        val memberProfile = roomMembers?.get(sender)
                                        //Log.d("Andromuks", "SpaceRoomParser: Looking up sender '$sender' in room '$roomId', found profile: $memberProfile")
                                        messageSender = sender // Always use Matrix ID for profile lookup
                                        //Log.d("Andromuks", "SpaceRoomParser: Using Matrix ID for messageSender: $sender")
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
                                        //Log.d("Andromuks", "SpaceRoomParser: Processing encrypted m.room.message event for room $roomId")
                                        //Log.d("Andromuks", "SpaceRoomParser: Encrypted event JSON: ${event.toString()}")
                                        if (sender.isNotBlank()) {
                                            // Try to get display name from member cache using full Matrix ID
                                            val roomMembers = memberCache?.get(roomId)
                                            val memberProfile = roomMembers?.get(sender)
                                            //Log.d("Andromuks", "SpaceRoomParser: Looking up encrypted sender '$sender' in room '$roomId', found profile: $memberProfile")
                                            messageSender = sender // Always use Matrix ID for profile lookup
                                            //Log.d("Andromuks", "SpaceRoomParser: Using Matrix ID for encrypted messageSender: $sender")
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
            
            // Extract tags from account_data.m.tag
            var isFavourite = false
            var isLowPriority = false
            
            val accountData = roomObj.optJSONObject("account_data")
            if (accountData != null) {
                val tagData = accountData.optJSONObject("m.tag")
                if (tagData != null) {
                    val content = tagData.optJSONObject("content")
                    if (content != null) {
                        val tags = content.optJSONObject("tags")
                        if (tags != null) {
                            // Check for m.favourite tag
                            if (tags.has("m.favourite")) {
                                isFavourite = true
                                //Log.d("Andromuks", "SpaceRoomParser: Room $roomId marked as favourite")
                            }
                            
                            // Check for m.lowpriority tag
                            if (tags.has("m.lowpriority")) {
                                isLowPriority = true
                                //Log.d("Andromuks", "SpaceRoomParser: Room $roomId marked as low priority")
                            }
                        }
                    }
                }
            }
            
            return RoomItem(
                id = roomId,
                name = name,
                messagePreview = messagePreview,
                messageSender = messageSender,
                unreadCount = if (unreadMessages > 0) unreadMessages else null,
                highlightCount = if (unreadHighlights > 0) unreadHighlights else null,
                avatarUrl = avatar,
                sortingTimestamp = sortingTimestamp,
                isDirectMessage = isDirectMessage,
                isFavourite = isFavourite,
                isLowPriority = isLowPriority
            )
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error parsing room $roomId", e)
            return null
        }
    }
    
    /**
     * Detects if a room is a Direct Message (DM) using multiple methods:
     * 1. Primary: dm_user_id field in meta (most reliable for gomuks JSON)
     * 2. Secondary: m.direct account data (more reliable than name-based detection)
     * 3. Fallback: room name patterns (contains @ symbol or looks like a user ID)
     */
    private fun detectDirectMessage(roomId: String, roomObj: JSONObject, meta: JSONObject, appViewModel: net.vrkknn.andromuks.AppViewModel? = null): Boolean {
        try {
            // Method 1: Check if dm_user_id is populated in meta - this indicates a DM
            val dmUserId = meta.optString("dm_user_id")?.takeIf { it.isNotBlank() }
            
            if (dmUserId != null) {
                //Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as DM (dm_user_id: $dmUserId)")
                return true
            }
            
            // Method 2: Check m.direct account data (secondary method)
            if (appViewModel != null && appViewModel.isDirectMessageFromAccountData(roomId)) {
                //Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as DM (m.direct account data)")
                return true
            }
            
            // Method 3: Fallback - Check if room name is exactly a Matrix user ID (not just contains @)
            val roomName = meta.optString("name", "")
            val isExactMatrixUserId = roomName.matches(Regex("^@[^:]+:[^:]+$")) // Exact Matrix user ID format
            
            if (isExactMatrixUserId) {
                //Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as DM (fallback: name is exact Matrix user ID: '$roomName')")
                return true
            }
            
            //Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as group room (no dm_user_id, not in m.direct, name: '$roomName')")
            return false
            
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error detecting DM status for room $roomId", e)
            return false
        }
    }
    
    /**
     * Parses basic space info from sync data (without edges)
     */
    private fun parseSpacesBasic(data: JSONObject): List<net.vrkknn.andromuks.SpaceItem> {
        val spaces = mutableListOf<net.vrkknn.andromuks.SpaceItem>()
        
        try {
            // Get top_level_spaces array from sync data
            val topLevelSpaces = data.optJSONArray("top_level_spaces")
            if (topLevelSpaces != null) {
                //Log.d("Andromuks", "SpaceRoomParser: Found top_level_spaces with ${topLevelSpaces.length()} spaces")
                //Log.d("Andromuks", "SpaceRoomParser: top_level_spaces content: ${topLevelSpaces.toString()}")
                
                for (i in 0 until topLevelSpaces.length()) {
                    val spaceId = topLevelSpaces.optString(i)
                    if (spaceId.isNotBlank()) {
                        // Try to get space details from rooms data
                        val roomsJson = data.optJSONObject("rooms")
                        val spaceDetails = roomsJson?.optJSONObject(spaceId)
                        val meta = spaceDetails?.optJSONObject("meta")
                        
                        val name = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: spaceId
                        val avatar = meta?.optString("avatar")?.takeIf { it.isNotBlank() }
                        
                        val spaceItem = net.vrkknn.andromuks.SpaceItem(
                            id = spaceId,
                            name = name,
                            avatarUrl = avatar,
                            rooms = emptyList() // No rooms yet - will be populated later
                        )
                        spaces.add(spaceItem)
                        //Log.d("Andromuks", "SpaceRoomParser: Found space: $name (ID: $spaceId) - basic info only")
                    }
                }
            }// else {
            //    Log.d("Andromuks", "SpaceRoomParser: No top_level_spaces found in sync data")
            //}
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error parsing spaces", e)
        }
        
        //Log.d("Andromuks", "SpaceRoomParser: Parsed ${spaces.size} spaces (basic)")
        return spaces
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
                //Log.d("Andromuks", "SpaceRoomParser: Found top_level_spaces with ${topLevelSpaces.length()} spaces")
                //Log.d("Andromuks", "SpaceRoomParser: top_level_spaces content: ${topLevelSpaces.toString()}")
                
                // Parse space_edges to get child rooms for each space
                val spaceEdges = data.optJSONObject("space_edges")
                //Log.d("Andromuks", "SpaceRoomParser: space_edges found: ${spaceEdges != null}")
                //if (spaceEdges != null) {
                //    Log.d("Andromuks", "SpaceRoomParser: space_edges keys: ${spaceEdges.keys()}")
                //}
                
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
                                //Log.d("Andromuks", "SpaceRoomParser: Space $spaceId has ${spaceEdgeArray.length()} child rooms")
                                //Log.d("Andromuks", "SpaceRoomParser: Space $spaceId edges: ${spaceEdgeArray.toString()}")
                                for (j in 0 until spaceEdgeArray.length()) {
                                    val edge = spaceEdgeArray.optJSONObject(j)
                                    val childId = edge?.optString("child_id")?.takeIf { it.isNotBlank() }
                                    if (childId != null) {
                                        // Try to find this room in the rooms data
                                        val childRoomData = roomsJson?.optJSONObject(childId)
                                        //Log.d("Andromuks", "SpaceRoomParser: Looking for child room $childId in rooms data: ${childRoomData != null}")
                                        if (childRoomData != null) {
                                            val childMeta = childRoomData.optJSONObject("meta")
                                            val childName = childMeta?.optString("name")?.takeIf { it.isNotBlank() } ?: childId
                                            val childAvatar = childMeta?.optString("avatar")?.takeIf { it.isNotBlank() }
                                            val unreadCount = childMeta?.optInt("unread_messages", 0) ?: 0
                                            val highlightCount = childMeta?.optInt("unread_highlights", 0) ?: 0
                                            
                                            // Check if this child is a space (has space_edges) - if so, skip it
                                            val isChildSpace = spaceEdges.has(childId)
                                            if (isChildSpace) {
                                                //Log.d("Andromuks", "SpaceRoomParser: Skipping child space: $childName")
                                            } else {
                                                val childRoom = net.vrkknn.andromuks.RoomItem(
                                                    id = childId,
                                                    name = childName,
                                                    avatarUrl = childAvatar,
                                                    unreadCount = if (unreadCount > 0) unreadCount else null,
                                                    highlightCount = if (highlightCount > 0) highlightCount else null,
                                                    messagePreview = null,
                                                    messageSender = null,
                                                    isDirectMessage = false
                                                )
                                                childRooms.add(childRoom)
                                                //Log.d("Andromuks", "SpaceRoomParser: Added child room: $childName (unread: $unreadCount)")
                                            }
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
                        //Log.d("Andromuks", "SpaceRoomParser: Found space: $name (ID: $spaceId) with ${childRooms.size} rooms")
                    }
                }
            }// else {
            //    Log.d("Andromuks", "SpaceRoomParser: No top_level_spaces found in sync data")
            //}
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error parsing spaces", e)
        }
        
        //Log.d("Andromuks", "SpaceRoomParser: Parsed ${spaces.size} spaces")
        return spaces
    }
    
    /**
     * Updates existing spaces with child rooms from space_edges
     */
    fun updateExistingSpacesWithEdges(spaceEdges: JSONObject, data: JSONObject, appViewModel: net.vrkknn.andromuks.AppViewModel?) {
        try {
            val roomsJson = data.optJSONObject("rooms")
            val updatedSpaces = mutableListOf<net.vrkknn.andromuks.SpaceItem>()
            
            // Get current spaces from AppViewModel
            val currentSpaces = appViewModel?.allSpaces ?: emptyList()
            //android.util.Log.d("Andromuks", "SpaceRoomParser: Updating ${currentSpaces.size} existing spaces with edges")
            
            for (space in currentSpaces) {
                val spaceEdgeArray = spaceEdges.optJSONArray(space.id)
                val childRooms = mutableListOf<net.vrkknn.andromuks.RoomItem>()
                
                if (spaceEdgeArray != null) {
                    //android.util.Log.d("Andromuks", "SpaceRoomParser: Space ${space.name} has ${spaceEdgeArray.length()} child rooms")
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
                                val highlightCount = childMeta?.optInt("unread_highlights", 0) ?: 0
                                
                                // Check if this child is a space (has space_edges) - if so, skip it
                                val isChildSpace = spaceEdges.has(childId)
                                if (isChildSpace) {
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "SpaceRoomParser: Skipping child space: $childName")
                                } else {
                                    val childRoom = net.vrkknn.andromuks.RoomItem(
                                        id = childId,
                                        name = childName,
                                        avatarUrl = childAvatar,
                                        unreadCount = if (unreadCount > 0) unreadCount else null,
                                        highlightCount = if (highlightCount > 0) highlightCount else null,
                                        messagePreview = null,
                                        messageSender = null,
                                        isDirectMessage = false
                                    )
                                    childRooms.add(childRoom)
                                    //android.util.Log.d("Andromuks", "SpaceRoomParser: Added child room: $childName (unread: $unreadCount)")
                                }
                            }
                        }
                    }
                }
                
                // Create updated space with new child rooms
                val updatedSpace = space.copy(rooms = childRooms)
                updatedSpaces.add(updatedSpace)
                //android.util.Log.d("Andromuks", "SpaceRoomParser: Updated space ${space.name} with ${childRooms.size} rooms")
            }
            
            // Update the spaces in AppViewModel
            appViewModel?.updateAllSpaces(updatedSpaces)
            //android.util.Log.d("Andromuks", "SpaceRoomParser: Updated ${updatedSpaces.size} spaces with edges")
            
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "SpaceRoomParser: Error updating spaces with edges", e)
        }
    }
    
}