package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Room list sections, badges, DM resolution, and debounced reorder for [AppViewModel].
 */
internal class RoomListUiCoordinator(private val vm: AppViewModel) {

    fun invalidateRoomSectionCache() {
        with(vm) {
            roomDataVersion++
        }
    }

    private fun currentSpaceIds(): Set<String> {
        with(vm) {
            val ids = mutableSetOf<String>()
            if (allSpaces.isNotEmpty()) ids.addAll(allSpaces.map { it.id })
            else if (spaceList.isNotEmpty()) ids.addAll(spaceList.map { it.id })
            ids.addAll(knownSpaceIds)
            return ids
        }
    }

    private fun filterOutSpaces(
        rooms: List<RoomItem>,
        spaceIds: Set<String> = currentSpaceIds(),
    ): List<RoomItem> {
        if (spaceIds.isEmpty()) return rooms
        return rooms.filter { it.id !in spaceIds }
    }

    private fun updateCachedRoomSections() {
        with(vm) {
            if (lastCachedVersion == roomDataVersion) return
            lastCachedVersion = roomDataVersion

            val roomsToUse =
                if (allRooms.isEmpty() && spaceList.isNotEmpty()) {
                    spaceList.firstOrNull()?.rooms ?: emptyList()
                } else {
                    allRooms
                }
            val spaceIds = this@RoomListUiCoordinator.currentSpaceIds()
            val roomsWithoutSpaces =
                this@RoomListUiCoordinator.filterOutSpaces(roomsToUse, spaceIds)

            this@RoomListUiCoordinator.updateBadgeCounts(roomsWithoutSpaces)

            cachedDirectChatRooms = roomsWithoutSpaces.filter { it.isDirectMessage }
            cachedUnreadRooms =
                roomsWithoutSpaces.filter {
                    (it.unreadCount != null && it.unreadCount > 0) ||
                        (it.highlightCount != null && it.highlightCount > 0)
                }
            cachedFavouriteRooms = roomsWithoutSpaces.filter { it.isFavourite }

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Updated cached sections - allRooms(raw): ${roomsToUse.size}, spaces filtered: ${roomsWithoutSpaces.size}, DMs: ${cachedDirectChatRooms.size}, Unread: ${cachedUnreadRooms.size}, Favourites: ${cachedFavouriteRooms.size}",
                )
        }
    }

    private fun updateBadgeCounts(rooms: List<RoomItem>) {
        with(vm) {
            var directChatsUnread = 0
            var directChatsHighlights = false
            var unreadCount = 0
            var favouritesUnread = 0
            var favouritesHighlights = false

            for (room in rooms) {
                val hasUnread =
                    (room.unreadCount != null && room.unreadCount > 0) ||
                        (room.highlightCount != null && room.highlightCount > 0)
                val hasHighlights = room.highlightCount != null && room.highlightCount > 0

                if (hasUnread) {
                    unreadCount++
                }

                if (room.isDirectMessage) {
                    if (hasUnread) {
                        directChatsUnread++
                        if (hasHighlights) {
                            directChatsHighlights = true
                        }
                    }
                }

                if (room.isFavourite) {
                    if (hasUnread) {
                        favouritesUnread++
                        if (hasHighlights) {
                            favouritesHighlights = true
                        }
                    }
                }
            }

            cachedDirectChatsUnreadCount = directChatsUnread
            cachedDirectChatsHasHighlights = directChatsHighlights
            cachedUnreadCount = unreadCount
            cachedFavouritesUnreadCount = favouritesUnread
            cachedFavouritesHasHighlights = favouritesHighlights

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Badge counts - DMs: $directChatsUnread, Unread: $unreadCount, Favs: $favouritesUnread",
                )
        }
    }

    fun getDirectRoomIdForUser(userId: String): String? {
        with(vm) {
            if (userId.isBlank()) return null

            val normalizedUserId = if (userId.startsWith("@")) userId else "@$userId"
            val candidateRooms = mutableListOf<RoomItem>()

            directMessageUserMap[normalizedUserId]?.forEach { roomId ->
                roomMap[roomId]?.let { candidateRooms.add(it) }
            }

            if (candidateRooms.isEmpty()) {
                val roomsToCheck =
                    if (cachedDirectChatRooms.isNotEmpty()) {
                        cachedDirectChatRooms
                    } else {
                        allRooms.filter { it.isDirectMessage }
                    }

                for (room in roomsToCheck) {
                    try {
                        val members = getMemberMap(room.id)
                        if (members.containsKey(normalizedUserId)) {
                            candidateRooms.add(room)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Failed to inspect members for room ${room.id} when resolving DM for $normalizedUserId",
                            e,
                        )
                    }
                }
            }

            if (candidateRooms.isEmpty()) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: No direct room found for user $normalizedUserId",
                )
            }

            return candidateRooms.maxByOrNull { it.sortingTimestamp ?: 0L }?.id
        }
    }

    fun getDirectRoomIdsForUser(userId: String): Set<String> {
        with(vm) {
            if (userId.isBlank()) return emptySet()

            val normalizedUserId = if (userId.startsWith("@")) userId else "@$userId"

            val fromAccountData = directMessageUserMap[normalizedUserId]
            if (fromAccountData != null && fromAccountData.isNotEmpty()) {
                return fromAccountData
            }

            if (directMessageUserMap.isEmpty()) {
                val roomsToCheck =
                    if (cachedDirectChatRooms.isNotEmpty()) {
                        cachedDirectChatRooms
                    } else {
                        allRooms.filter { it.isDirectMessage }
                    }

                val foundRooms = mutableSetOf<String>()
                for (room in roomsToCheck) {
                    try {
                        val members = getMemberMap(room.id)
                        if (members.containsKey(normalizedUserId)) {
                            foundRooms.add(room.id)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Failed to inspect members for room ${room.id} when resolving DM for $normalizedUserId",
                            e,
                        )
                    }
                }

                if (foundRooms.isNotEmpty()) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Found ${foundRooms.size} DM rooms for $normalizedUserId via fallback scanning (account_data not loaded yet)",
                        )
                    }
                    return foundRooms
                }
            }

            return emptySet()
        }
    }

    fun buildDirectPersonTargets(rooms: List<RoomItem>): List<PersonTarget> {
        with(vm) {
            if (currentUserId.isBlank()) {
                return emptyList()
            }

            val result = mutableMapOf<String, PersonTarget>()

            for (room in rooms) {
                if (!room.isDirectMessage) continue

                val timestamp = room.sortingTimestamp ?: 0L
                val roomDisplayName = room.name
                var foundOtherMember = false

                val memberMap =
                    try {
                        getMemberMap(room.id)
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Failed to get member map for ${room.id}",
                            e,
                        )
                        emptyMap()
                    }

                for ((userId, profile) in memberMap) {
                    if (userId == currentUserId) continue
                    foundOtherMember = true
                    val displayName =
                        profile.displayName?.takeIf { it.isNotBlank() }
                            ?: roomDisplayName.ifBlank { userId }
                    val existing = result[userId]
                    if (existing == null || timestamp > existing.lastActiveTimestamp) {
                        result[userId] =
                            PersonTarget(
                                userId = userId,
                                displayName = displayName,
                                avatarUrl = profile.avatarUrl,
                                roomId = room.id,
                                roomDisplayName = roomDisplayName,
                                lastActiveTimestamp = timestamp,
                            )
                    }
                }

                if (!foundOtherMember) {
                    val inferredUserId = room.messageSender
                    if (!inferredUserId.isNullOrBlank() && inferredUserId != currentUserId) {
                        val displayName = roomDisplayName.ifBlank { inferredUserId }
                        val existing = result[inferredUserId]
                        if (existing == null || timestamp > existing.lastActiveTimestamp) {
                            result[inferredUserId] =
                                PersonTarget(
                                    userId = inferredUserId,
                                    displayName = displayName,
                                    avatarUrl = null,
                                    roomId = room.id,
                                    roomDisplayName = roomDisplayName,
                                    lastActiveTimestamp = timestamp,
                                )
                        }
                    }
                }
            }

            return result.values.sortedByDescending { it.lastActiveTimestamp }
        }
    }

    fun getCurrentRoomSection(): RoomSection {
        with(vm) {
            if (!loadedSections.contains(selectedSection)) {
                loadedSections.add(selectedSection)
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Lazy loading section: $selectedSection",
                    )

                if (loadedSections.size == 1 && selectedSection != RoomSectionType.HOME) {
                    loadedSections.add(RoomSectionType.HOME)
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Auto-loading HOME section for badge counts",
                        )
                }

                invalidateRoomSectionCache()
            }

            this@RoomListUiCoordinator.updateCachedRoomSections()

            val roomsToUse =
                if (allRooms.isEmpty() && spaceList.isNotEmpty()) {
                    spaceList.firstOrNull()?.rooms ?: emptyList()
                } else {
                    allRooms
                }
            val roomsWithoutSpaces =
                this@RoomListUiCoordinator.filterOutSpaces(roomsToUse)

            return when (selectedSection) {
                RoomSectionType.HOME ->
                    RoomSection(type = RoomSectionType.HOME, rooms = roomsWithoutSpaces)
                RoomSectionType.SPACES -> {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: SPACES section - currentSpaceId = $currentSpaceId, allSpaces.size = ${allSpaces.size}",
                        )
                    if (currentSpaceId != null) {
                        val selectedSpace = allSpaces.find { it.id == currentSpaceId }
                        val spaceRooms = selectedSpace?.rooms ?: emptyList()
                        val enrichedSpaceRooms =
                            spaceRooms.mapNotNull { room ->
                                roomMap[room.id] ?: allRooms.firstOrNull { it.id == room.id } ?: room
                            }
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Selected space = $selectedSpace, rooms.size = ${spaceRooms.size}, enriched.size=${enrichedSpaceRooms.size}",
                            )
                        RoomSection(
                            type = RoomSectionType.SPACES,
                            rooms = enrichedSpaceRooms,
                            spaces = emptyList(),
                        )
                    } else {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Showing space list with ${allSpaces.size} spaces",
                            )
                        RoomSection(
                            type = RoomSectionType.SPACES,
                            rooms = emptyList(),
                            spaces = allSpaces,
                        )
                    }
                }
                RoomSectionType.DIRECT_CHATS -> {
                    val unreadDmCount =
                        cachedDirectChatRooms.count {
                            (it.unreadCount != null && it.unreadCount > 0) ||
                                (it.highlightCount != null && it.highlightCount > 0)
                        }
                    RoomSection(
                        type = RoomSectionType.DIRECT_CHATS,
                        rooms = cachedDirectChatRooms,
                        unreadCount = unreadDmCount,
                    )
                }
                RoomSectionType.UNREAD ->
                    RoomSection(
                        type = RoomSectionType.UNREAD,
                        rooms = cachedUnreadRooms,
                        unreadCount = cachedUnreadRooms.size,
                    )
                RoomSectionType.MENTIONS ->
                    RoomSection(RoomSectionType.MENTIONS, rooms = emptyList())
                RoomSectionType.FAVOURITES -> {
                    val unreadFavouriteCount =
                        cachedFavouriteRooms.count {
                            (it.unreadCount != null && it.unreadCount > 0) ||
                                (it.highlightCount != null && it.highlightCount > 0)
                        }
                    RoomSection(
                        type = RoomSectionType.FAVOURITES,
                        rooms = cachedFavouriteRooms,
                        unreadCount = unreadFavouriteCount,
                    )
                }
                RoomSectionType.BRIDGES -> {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: BRIDGES section - currentBridgeId = $currentBridgeId",
                        )

                    val bridgedRooms =
                        roomsWithoutSpaces.filter { it.bridgeProtocolAvatarUrl != null }
                    val bridgeGroups = bridgedRooms.groupBy { it.bridgeProtocolAvatarUrl!! }

                    val bridgeSpaces =
                        bridgeGroups.map { (bridgeAvatarUrl, rooms) ->
                            val bridgeName =
                                rooms.firstOrNull()?.let { room ->
                                    val context = appContext
                                    if (context != null) {
                                        val cachedDisplayName =
                                            net.vrkknn.andromuks.utils.BridgeInfoCache
                                                .getBridgeDisplayName(context, room.id)
                                        if (cachedDisplayName != null) {
                                            if (BuildConfig.DEBUG)
                                                android.util.Log.d(
                                                    "Andromuks",
                                                    "AppViewModel: BRIDGES - Found cached display name for room ${room.id}: $cachedDisplayName",
                                                )
                                            return@let cachedDisplayName
                                        }
                                    }

                                    getBridgeDisplayNameFromRoomState(room.id) ?: "Bridge"
                                } ?: "Bridge"

                            if (BuildConfig.DEBUG && bridgeName == "Bridge") {
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: BRIDGES - Using fallback name 'Bridge' for bridge with avatar $bridgeAvatarUrl (room: ${rooms.firstOrNull()?.id})",
                                )
                            }

                            SpaceItem(
                                id = bridgeAvatarUrl,
                                name = bridgeName,
                                avatarUrl = bridgeAvatarUrl,
                                rooms = rooms,
                            )
                        }

                    if (currentBridgeId != null) {
                        val selectedBridge = bridgeSpaces.find { it.id == currentBridgeId }
                        val bridgeRooms = selectedBridge?.rooms ?: emptyList()
                        val enrichedBridgeRooms =
                            bridgeRooms.mapNotNull { room ->
                                roomMap[room.id] ?: allRooms.firstOrNull { it.id == room.id } ?: room
                            }
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Selected bridge = $selectedBridge, rooms.size = ${bridgeRooms.size}, enriched.size=${enrichedBridgeRooms.size}",
                            )
                        RoomSection(
                            type = RoomSectionType.BRIDGES,
                            rooms = enrichedBridgeRooms,
                            spaces = emptyList(),
                        )
                    } else {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Showing bridge list with ${bridgeSpaces.size} bridges",
                            )
                        RoomSection(
                            type = RoomSectionType.BRIDGES,
                            rooms = emptyList(),
                            spaces = bridgeSpaces,
                        )
                    }
                }
            }
        }
    }

    fun scheduleRoomReorder(forceImmediate: Boolean = false) {
        with(vm) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastReorder = currentTime - lastRoomReorderTime

            roomReorderJob?.cancel()

            if (forceImmediate || timeSinceLastReorder >= ROOM_REORDER_DEBOUNCE_MS) {
                this@RoomListUiCoordinator.performRoomReorder()
            } else {
                val delayMs = ROOM_REORDER_DEBOUNCE_MS - timeSinceLastReorder
                roomReorderJob =
                    viewModelScope.launch {
                        delay(delayMs)
                        this@RoomListUiCoordinator.performRoomReorder()
                    }
            }
        }
    }

    private fun performRoomReorder() {
        with(vm) {
            lastRoomReorderTime = System.currentTimeMillis()

            val newlyJoinedRooms = roomMap.values.filter { newlyJoinedRoomIds.contains(it.id) }
            val existingRooms = roomMap.values.filter { !newlyJoinedRoomIds.contains(it.id) }

            val sortedExistingRooms =
                existingRooms.sortedByDescending { it.sortingTimestamp ?: 0L }
            val sortedNewlyJoinedRooms =
                newlyJoinedRooms.sortedByDescending { it.sortingTimestamp ?: 0L }
            val sortedRooms = sortedNewlyJoinedRooms + sortedExistingRooms

            if (newlyJoinedRoomIds.isNotEmpty()) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Sorting ${newlyJoinedRoomIds.size} newly joined rooms to the top",
                    )
                newlyJoinedRoomIds.clear()
            }

            val currentRoomIds = allRooms.map { it.id }
            val newRoomIds = sortedRooms.map { it.id }
            val orderChanged = currentRoomIds != newRoomIds

            if (orderChanged || allRooms.size != sortedRooms.size) {
                setSpaces(
                    listOf(
                        SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = sortedRooms),
                    ),
                    skipCounterUpdate = true,
                )
                allRooms = sortedRooms
                invalidateRoomSectionCache()

                needsRoomListUpdate = true
                scheduleUIUpdate("roomList")
            }
        }
    }
}
