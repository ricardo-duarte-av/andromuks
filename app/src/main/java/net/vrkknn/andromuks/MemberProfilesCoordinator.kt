package net.vrkknn.andromuks

import org.json.JSONArray
import org.json.JSONObject

/**
 * Member maps, profile storage, and full member-list handling for [AppViewModel].
 */
internal class MemberProfilesCoordinator(private val vm: AppViewModel) {

    fun getMemberMap(roomId: String): Map<String, MemberProfile> {
        with(vm) {
            val memberMap = mutableMapOf<String, MemberProfile>()

            val userIds = ProfileCache.getRoomUserIds(roomId)
            if (userIds != null && userIds.isNotEmpty()) {
                for (userId in userIds) {
                    val profile = ProfileCache.getFlattenedProfile(roomId, userId)
                    if (profile != null) {
                        memberMap[userId] = profile
                    } else {
                        val globalProfile = ProfileCache.getGlobalProfileProfile(userId)
                        if (globalProfile != null) {
                            ProfileCache.updateGlobalProfileAccess(userId)
                            memberMap[userId] = globalProfile
                        }
                    }
                }
            } else {
                val legacyMap = RoomMemberCache.getRoomMembers(roomId)
                if (legacyMap.isNotEmpty()) {
                    memberMap.putAll(legacyMap)
                }
            }

            val eventsToCheck =
                if (currentRoomId == roomId && timelineEvents.isNotEmpty()) {
                    timelineEvents
                } else {
                    RoomTimelineCache.getCachedEvents(roomId) ?: emptyList()
                }

            if (eventsToCheck.isNotEmpty()) {
                for (event in eventsToCheck) {
                    val sender = event.sender
                    if (!memberMap.containsKey(sender)) {
                        val globalProfile = ProfileCache.getGlobalProfileProfile(sender)
                        if (globalProfile != null) {
                            ProfileCache.updateGlobalProfileAccess(sender)
                            memberMap[sender] = globalProfile
                            ProfileCache.addToRoomIndex(roomId, sender)
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Added global profile fallback for $sender in room $roomId via getMemberMap()",
                                )
                        }
                    }
                }
            }

            return memberMap
        }
    }

    fun getMemberMapWithFallback(
        roomId: String,
        timelineEvents: List<TimelineEvent>? = null,
    ): Map<String, MemberProfile> {
        val memberMap = getMemberMap(roomId).toMutableMap()
        with(vm) {
            timelineEvents?.let { events ->
                for (event in events) {
                    val sender = event.sender
                    if (!memberMap.containsKey(sender)) {
                        val globalProfile = ProfileCache.getGlobalProfileProfile(sender)
                        if (globalProfile != null) {
                            ProfileCache.updateGlobalProfileAccess(sender)
                            memberMap[sender] = globalProfile
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Added global profile fallback for $sender in room $roomId",
                                )
                        }
                    }
                }
            }
        }

        return memberMap.mapValues { (userId, profile) ->
            val fallbackName = userId.removePrefix("@").substringBefore(":")
            MemberProfile(
                displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: fallbackName,
                avatarUrl = profile.avatarUrl,
            )
        }
    }

    fun storeMemberProfile(roomId: String, userId: String, profile: MemberProfile) {
        val existingGlobalProfile = ProfileCache.getGlobalProfileProfile(userId)

        if (existingGlobalProfile == null) {
            // No global profile known — m.room.member is always room-scoped, never promote it to global.
            // Only get_profile responses (via updateGlobalProfile) may write to globalProfileCache.
            ProfileCache.setFlattenedProfile(roomId, userId, profile)
            ProfileCache.addToRoomIndex(roomId, userId)
        } else {
            val profilesDiffer =
                existingGlobalProfile.displayName != profile.displayName ||
                    existingGlobalProfile.avatarUrl != profile.avatarUrl

            if (profilesDiffer) {
                ProfileCache.setFlattenedProfile(roomId, userId, profile)
                ProfileCache.addToRoomIndex(roomId, userId)
            } else {
                // Room profile matches global — no separate room entry needed.
                ProfileCache.removeFlattenedProfile(roomId, userId)
                ProfileCache.removeFromRoomIndex(roomId, userId)
            }
        }

        RoomMemberCache.updateMember(roomId, userId, profile)

        if (ProfileCache.getFlattenedCacheSize() > AppViewModel.MAX_MEMBER_CACHE_SIZE) {
            performMemberCacheCleanup()
        }
    }

    /** Called from [AppViewModel.performPeriodicMemoryCleanup] and [storeMemberProfile]. */
    internal fun performMemberCacheCleanup() {
        ProfileCache.cleanupGlobalProfiles(AppViewModel.MAX_MEMBER_CACHE_SIZE)
        ProfileCache.cleanupFlattenedProfiles(AppViewModel.MAX_MEMBER_CACHE_SIZE)

        if (BuildConfig.DEBUG)
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Performed member cache cleanup - flattened: ${ProfileCache.getFlattenedCacheSize()}, global: ${ProfileCache.getGlobalCacheSize()}",
            )
    }

    fun manageRoomMemberCacheSize(roomId: String) {
        val memberMap = RoomMemberCache.getRoomMembers(roomId)
        if (memberMap.size > 500) {
            val keysToRemove = memberMap.keys.take(250)
            keysToRemove.forEach { userId ->
                RoomMemberCache.removeMember(roomId, userId)
            }
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Cleared ${keysToRemove.size} old entries from room $roomId cache",
            )
        }
    }

    fun handleFullMemberListResponse(requestId: Int, data: Any) {
        with(vm) {
            val roomId = fullMemberListRequests.remove(requestId) ?: return

            pendingFullMemberListRequests.remove(roomId)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Handling full member list response for room: $roomId",
                )

            val currentState =
                navigationCache[roomId] ?: AppViewModel.RoomNavigationState(roomId)
            navigationCache[roomId] =
                currentState.copy(
                    memberDataLoaded = true,
                    lastPrefetchTime = System.currentTimeMillis(),
                )

            when (data) {
                is JSONArray -> {
                    this@MemberProfilesCoordinator.parseFullMemberListFromRoomState(roomId, data)
                }
                else -> {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Unhandled data type in handleFullMemberListResponse: ${data::class.java.simpleName}",
                        )
                }
            }
        }
    }

    private fun parseFullMemberListFromRoomState(roomId: String, events: JSONArray) {
        with(vm) {
            val startTime = System.currentTimeMillis()
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Parsing full member list from ${events.length()} room state events for room: $roomId",
                )

            val previousSize = RoomMemberCache.getRoomMembers(roomId).size
            RoomMemberCache.clearRoom(roomId)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Cleared $previousSize existing members from cache for fresh member list",
                )

            val memberMap = mutableMapOf<String, MemberProfile>()

            var updatedMembers = 0
            val isLargeRoom = events.length() > 100
            var cacheCleared = false

            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventType = event.optString("type")

                if (eventType == "m.room.member") {
                    val stateKey = event.optString("state_key")
                    val content = event.optJSONObject("content")
                    val membership = content?.optString("membership")

                    if (stateKey.isNotEmpty()) {
                        when (membership) {
                            "join" -> {
                                val displayName =
                                    content?.optString("displayname")?.takeIf { it.isNotBlank() && it != "null" } ?: ""
                                val avatarUrl =
                                    content?.optString("avatar_url")?.takeIf { it.isNotBlank() && it != "null" } ?: ""

                                val newProfile = MemberProfile(displayName, avatarUrl)
                                memberMap[stateKey] = newProfile

                                RoomMemberCache.updateMember(roomId, stateKey, newProfile)

                                this@MemberProfilesCoordinator.storeMemberProfile(
                                    roomId,
                                    stateKey,
                                    newProfile,
                                )

                                if (!isLargeRoom || updatedMembers % 50 == 0) {
                                    manageGlobalCacheSize()
                                    this@MemberProfilesCoordinator.manageRoomMemberCacheSize(roomId)
                                    manageFlattenedMemberCacheSize()
                                }

                                if (BuildConfig.DEBUG && updatedMembers < 20) {
                                    android.util.Log.d(
                                        "Andromuks",
                                        "AppViewModel: Added member $stateKey to room $roomId - displayName: '$displayName', avatarUrl: '$avatarUrl'",
                                    )
                                }
                                updatedMembers++

                                if (isLargeRoom) {
                                    if (updatedMembers > 500 && !cacheCleared) {
                                        ProfileCache.clear()
                                        cacheCleared = true
                                        android.util.Log.w(
                                            "Andromuks",
                                            "AppViewModel: Cleared all caches due to large member list (${updatedMembers}+ members)",
                                        )
                                    }
                                } else {
                                    queueProfileForBatchSave(stateKey, newProfile)
                                }
                            }
                            "leave", "ban" -> {
                                val wasRemoved = memberMap.remove(stateKey) != null
                                RoomMemberCache.removeMember(roomId, stateKey)
                                val wasRemovedFromFlattened =
                                    ProfileCache.hasFlattenedProfile(roomId, stateKey)
                                if (wasRemovedFromFlattened) {
                                    ProfileCache.removeFlattenedProfile(roomId, stateKey)
                                }

                                ProfileCache.removeFromRoomIndex(roomId, stateKey)

                                if (wasRemoved || wasRemovedFromFlattened) {
                                    if (BuildConfig.DEBUG)
                                        android.util.Log.d(
                                            "Andromuks",
                                            "AppViewModel: Removed $stateKey from room $roomId (membership: $membership)",
                                        )
                                    updatedMembers++
                                }
                            }
                        }
                    }
                }
            }

            if (updatedMembers > 0) {
                val duration = System.currentTimeMillis() - startTime
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Updated $updatedMembers members in full member list for room $roomId in ${duration}ms",
                    )
                updateCounter++
                memberUpdateCounter++
            }

            parseRoomStateFromEvents(roomId, events)
        }
    }

    fun requestUserProfile(userId: String, roomId: String?) {
        with(vm) {
            val globalRequestKey = userId

            if (pendingProfileRequests.contains(globalRequestKey)) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Global profile request already pending for $userId, skipping duplicate",
                    )
                return
            }

            val profile = getUserProfile(userId, roomId)
            if (profile != null && !profile.displayName.isNullOrBlank()) {
                return
            }

            if (!canSendCommandsToBackend) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Profile request for $userId deferred - WebSocket not ready (will be queued)",
                    )
                }
            }

            val reqId = requestIdCounter++

            pendingProfileRequests.add(userId)
            profileRequests[reqId] = userId
            if (roomId != null) {
                profileRequestRooms[reqId] = roomId
            }

            sendWebSocketCommand("get_profile", reqId, mapOf("user_id" to userId))
        }
    }

    fun requestBasicUserProfile(userId: String, callback: (MemberProfile?) -> Unit) {
        with(vm) {
            val cached = ProfileCache.getGlobalProfileProfile(userId)
            if (cached != null &&
                (!cached.displayName.isNullOrBlank() || !cached.avatarUrl.isNullOrBlank())
            ) {
                callback(cached)
                return
            }

            if (!isWebSocketConnected()) {
                callback(null)
                return
            }

            val reqId = requestIdCounter++
            pendingProfileRequests.add(userId)
            profileRequests[reqId] = userId
            basicProfileCallbacks[reqId] = callback
            sendWebSocketCommand("get_profile", reqId, mapOf("user_id" to userId))
        }
    }

    fun handleProfileError(requestId: Int, errorMessage: String) {
        with(vm) {
            val userId = profileRequests.remove(requestId) ?: return
            val requestingRoomId = profileRequestRooms.remove(requestId)

            pendingProfileRequests.remove(userId)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Profile lookup failed for $userId: $errorMessage",
                )

            val fullUserInfoCallback = fullUserInfoCallbacks.remove(requestId)
            if (fullUserInfoCallback != null) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Profile error for full user info request (requestId: $requestId, userId: $userId), invoking callback with null",
                    )
                fullUserInfoCallback(null)
            }

            val existingProfile = getUserProfile(userId, requestingRoomId)
            if (existingProfile != null && !existingProfile.displayName.isNullOrBlank()) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Global profile lookup failed for $userId, but we already have a profile from room state. Skipping fallback.",
                    )
                return
            }

            val username = userId.removePrefix("@").substringBefore(":")
            val memberProfile = MemberProfile(username, "")

            if (requestingRoomId != null) {
                this@MemberProfilesCoordinator.storeMemberProfile(
                    requestingRoomId,
                    userId,
                    memberProfile,
                )
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Added fallback profile for $userId to room $requestingRoomId",
                    )
            }

            RoomMemberCache.getAllMembers().forEach { (roomId, _) ->
                val existingMembers = RoomMemberCache.getRoomMembers(roomId)
                if (existingMembers.containsKey(userId)) {
                    this@MemberProfilesCoordinator.storeMemberProfile(roomId, userId, memberProfile)
                    RoomMemberCache.updateMember(roomId, userId, memberProfile)
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Updated member cache with username '$username' for $userId in room $roomId",
                        )
                }
            }

            needsMemberUpdate = true
            scheduleUIUpdate("member")
        }
    }

    fun handleProfileResponse(requestId: Int, data: Any) {
        with(vm) {
            val userId = profileRequests.remove(requestId)
            if (userId == null) {
                if (BuildConfig.DEBUG)
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: handleProfileResponse called for unknown requestId=$requestId",
                    )
                return
            }
            val requestingRoomId = profileRequestRooms.remove(requestId)
            val basicProfileCallback = basicProfileCallbacks.remove(requestId)

            pendingProfileRequests.remove(userId)
            val obj = data as? JSONObject
            if (obj == null) {
                basicProfileCallback?.invoke(null)
                return
            }
            val avatar = obj.optString("avatar_url")?.takeIf { it.isNotBlank() && it != "null" } ?: ""
            val display = obj.optString("displayname")?.takeIf { it.isNotBlank() && it != "null" } ?: ""

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: handleProfileResponse processing - userId: $userId, displayName: $display, avatarUrl: $avatar",
                )

            val memberProfile = MemberProfile(display, avatar)

            updateGlobalProfile(userId, memberProfile)

            if (requestingRoomId != null) {
                RoomMemberCache.updateMember(requestingRoomId, userId, memberProfile)
            }

            val allRooms = RoomMemberCache.getAllMembers()
            allRooms.forEach { (roomId, memberMap) ->
                if (memberMap.containsKey(userId)) {
                    RoomMemberCache.updateMember(roomId, userId, memberProfile)
                }
            }

            if (userId == currentUserId) {
                currentUserProfile = UserProfile(userId = userId, displayName = display, avatarUrl = avatar)
                persistCurrentUserAvatarMxcIfChanged(avatar)
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Updated currentUserProfile - userId: $userId, displayName: $display, avatarUrl: $avatar",
                    )
                // Profile was the last missing condition for startup — re-check now that it's set.
                checkStartupComplete()
            }

            queueProfileForBatchSave(userId, memberProfile)
            basicProfileCallback?.invoke(memberProfile)

            val fullUserInfoCallback = fullUserInfoCallbacks.remove(requestId)
            if (fullUserInfoCallback != null) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Invoking full user info callback for profile (requestId: $requestId, userId: $userId)",
                    )
                fullUserInfoCallback(obj)
            } else {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Profile response received but no full user info callback found (requestId: $requestId)",
                    )
            }

            needsMemberUpdate = true
            scheduleUIUpdate("member")
        }
    }
}
