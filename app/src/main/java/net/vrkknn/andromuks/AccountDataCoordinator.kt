package net.vrkknn.andromuks


/**
 * Account data: recent emojis, m.direct, ignored users, room tags — for [AppViewModel].
 */
internal class AccountDataCoordinator(private val vm: AppViewModel) {

    fun updateRecentEmojis(emoji: String) = with(vm) {
        if (!hasLoadedRecentEmojisFromServer) {
            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Skipping updateRecentEmojis - haven't loaded full recent emoji list from server yet. Will update after sync completes.")
            return@with
        }

        val currentFrequencies = recentEmojiFrequencies.toMutableList()
        val existingIndex = currentFrequencies.indexOfFirst { it.first == emoji }

        if (existingIndex >= 0) {
            val existingPair = currentFrequencies[existingIndex]
            val newCount = existingPair.second + 1
            currentFrequencies[existingIndex] = Pair(emoji, newCount)
        } else {
            currentFrequencies.add(Pair(emoji, 1))
        }

        val sortedFrequencies = currentFrequencies.sortedByDescending { it.second }
        val updatedFrequencies = sortedFrequencies.take(20)

        if (updatedFrequencies.isEmpty()) {
            android.util.Log.e("Andromuks", "AppViewModel: updateRecentEmojis resulted in empty list for emoji '$emoji' - this should never happen!")
            return@with
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Preparing to send emoji update - current list has ${recentEmojiFrequencies.size} emojis, updated list will have ${updatedFrequencies.size} emojis")
            android.util.Log.d("Andromuks", "AppViewModel: Emoji '$emoji' will have count ${updatedFrequencies.find { it.first == emoji }?.second ?: 1}")
            android.util.Log.d("Andromuks", "AppViewModel: Full list being sent: ${updatedFrequencies.joinToString(", ") { "${it.first}(${it.second})" }}")
        }

        sendAccountDataUpdate(updatedFrequencies)

        val emojisList = updatedFrequencies.map { it.first }
        RecentEmojisCache.set(emojisList)
        recentEmojis = emojisList
    }

    internal fun sendAccountDataUpdate(frequencies: List<Pair<String, Int>>) = with(vm) {
        WebSocketService.getWebSocket() ?: return@with

        if (frequencies.isEmpty()) {
            android.util.Log.w("Andromuks", "AppViewModel: sendAccountDataUpdate called with empty frequencies list - skipping send to prevent clearing recent emojis")
            return@with
        }

        val accountDataRequestId = requestIdCounter++

        val recentEmojiArray = frequencies.map { listOf(it.first, it.second) }

        val commandData = mapOf(
            "type" to "io.element.recent_emoji",
            "content" to mapOf(
                "recent_emoji" to recentEmojiArray
            )
        )

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: set_account_data with ${frequencies.size} emojis")
            android.util.Log.d("Andromuks", "AppViewModel: Emojis being sent: ${frequencies.joinToString(", ") { "${it.first}(${it.second})" }}")
            android.util.Log.d("Andromuks", "AppViewModel: Full command data: $commandData")
        }
        sendWebSocketCommand("set_account_data", accountDataRequestId, commandData)
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket command sent with request_id: $accountDataRequestId")
    }

    /**
     * Build a mutable copy of the current m.direct map (user MXID -> room IDs).
     */
    internal fun mutableDirectMapFromCurrent(): MutableMap<String, MutableList<String>> = with(vm) {
        val out = mutableMapOf<String, MutableList<String>>()
        for ((userId, roomSet) in directMessageUserMap) {
            out[userId] = roomSet.toMutableList()
        }
        val mDirectWrapper = AccountDataCache.getAccountData("m.direct") ?: return out
        val content = mDirectWrapper.optJSONObject("content") ?: return out
        val names = content.names() ?: return out
        for (i in 0 until names.length()) {
            val userId = names.optString(i)
            if (userId.isBlank()) continue
            val arr = content.optJSONArray(userId) ?: continue
            val list = out.getOrPut(userId) { mutableListOf() }
            for (j in 0 until arr.length()) {
                val id = arr.optString(j)
                if (id.isNotBlank() && id !in list) list.add(id)
            }
        }
        out
    }

    internal fun inferSingleOtherMemberMxid(roomId: String): String? = with(vm) {
        if (currentUserId.isBlank()) return@with null
        val others = getMemberMap(roomId).keys.filter { it.isNotBlank() && it != currentUserId }
        others.singleOrNull()
    }

    internal fun removeRoomFromAllDirectEntries(map: MutableMap<String, MutableList<String>>, roomId: String) {
        for (uid in map.keys.toList()) {
            map[uid]?.remove(roomId)
            if (map[uid].isNullOrEmpty()) map.remove(uid)
        }
    }

    internal fun commitMDirectToServer(map: MutableMap<String, MutableList<String>>) = with(vm) {
        val contentMap = mutableMapOf<String, Any>()
        for ((userId, rooms) in map) {
            if (rooms.isNotEmpty()) {
                contentMap[userId] = ArrayList(rooms)
            }
        }
        val requestId = requestIdCounter++
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: set_account_data m.direct — ${contentMap.size} user keys, ${map.values.sumOf { it.size }} room entries (local caches updated on next sync_complete)"
            )
        }
        sendWebSocketCommand(
            "set_account_data",
            requestId,
            mapOf(
                "type" to "m.direct",
                "content" to contentMap
            )
        )
    }

    fun setIgnoredUser(userId: String, ignore: Boolean) = with(vm) {
        val currentIgnored = ignoredUsers.toMutableSet()

        if (ignore) {
            currentIgnored.add(userId)
        } else {
            currentIgnored.remove(userId)
        }

        val ignoredUsersObj = org.json.JSONObject()
        currentIgnored.forEach { ignoredUserId ->
            ignoredUsersObj.put(ignoredUserId, org.json.JSONObject())
        }

        val ignoredUsersMap = mutableMapOf<String, Any>()
        val keys = ignoredUsersObj.names()
        if (keys != null) {
            for (i in 0 until keys.length()) {
                val key = keys.optString(i)
                val value = ignoredUsersObj.optJSONObject(key)
                if (value != null) {
                    ignoredUsersMap[key] = emptyMap<String, Any>()
                }
            }
        }

        val requestId = requestIdCounter++
        val commandData = mapOf(
            "type" to "m.ignored_user_list",
            "content" to mapOf(
                "ignored_users" to ignoredUsersMap
            )
        )

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Setting ignored users: ${if (ignore) "ignoring" else "unignoring"} $userId")
            android.util.Log.d("Andromuks", "AppViewModel: Total ignored users: ${currentIgnored.size}")
        }

        ignoredUsers = currentIgnored.toSet()

        sendWebSocketCommand("set_account_data", requestId, commandData)
    }

    fun setRoomTag(roomId: String, tagType: String, enabled: Boolean) = with(vm) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Setting room tag - roomId: $roomId, tagType: $tagType, enabled: $enabled")
        }

        val requestId = requestIdCounter++

        val existingRoom = roomMap[roomId]
        val existingTags = mutableMapOf<String, Map<String, Any>>()

        if (existingRoom != null) {
            if (existingRoom.isFavourite && tagType != "m.favourite") {
                existingTags["m.favourite"] = emptyMap()
            }
            if (existingRoom.isLowPriority && tagType != "m.lowpriority") {
                existingTags["m.lowpriority"] = emptyMap()
            }
        }

        if (enabled) {
            existingTags[tagType] = emptyMap()
        }

        val commandData = mapOf(
            "type" to "m.tag",
            "room_id" to roomId,
            "content" to mapOf(
                "tags" to existingTags
            )
        )

        val updatedRoom = existingRoom?.let { room ->
            when (tagType) {
                "m.favourite" -> room.copy(isFavourite = enabled)
                "m.lowpriority" -> room.copy(isLowPriority = enabled)
                else -> room
            }
        }

        if (updatedRoom != null) {
            roomMap[roomId] = updatedRoom
            RoomListCache.updateRoom(updatedRoom)
            forceRoomListSort()
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Sending set_account_data for room tag - commandData: $commandData")
        }

        sendWebSocketCommand("set_account_data", requestId, commandData)
    }
}
