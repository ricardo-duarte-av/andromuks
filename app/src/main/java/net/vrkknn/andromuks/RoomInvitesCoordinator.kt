package net.vrkknn.andromuks

/**
 * Room invites and join/leave orchestration for [AppViewModel].
 */
internal class RoomInvitesCoordinator(private val vm: AppViewModel) {

    fun acceptRoomInvite(roomId: String) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: Accepting room invite: $roomId")

            newlyJoinedRoomIds.add(roomId)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Preemptively marked room $roomId as newly joined"
                )

            val acceptRequestId = requestIdCounter++
            joinRoomRequests[acceptRequestId] = roomId
            val via = roomId.substringAfter(":").substringBefore(".")
            sendWebSocketCommand(
                "join_room",
                acceptRequestId,
                mapOf(
                    "room_id_or_alias" to roomId,
                    "via" to listOf(via)
                )
            )

            PendingInvitesCache.removeInvite(roomId)
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: Removed invite from memory: $roomId")

            roomListUpdateCounter++
            updateCounter++
        }
    }

    fun refuseRoomInvite(roomId: String) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: Refusing room invite: $roomId")

            val refuseRequestId = requestIdCounter++
            leaveRoomRequests[refuseRequestId] = roomId
            sendWebSocketCommand("leave_room", refuseRequestId, mapOf("room_id" to roomId))

            PendingInvitesCache.removeInvite(roomId)
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: Removed invite from memory: $roomId")

            roomListUpdateCounter++
            updateCounter++
        }
    }

    fun leaveRoom(roomId: String, reason: String? = null) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Leaving room: $roomId${if (reason != null) " with reason: $reason" else ""}"
                )

            val leaveRequestId = requestIdCounter++
            leaveRoomRequests[leaveRequestId] = roomId

            val commandData = mutableMapOf<String, Any>("room_id" to roomId)
            if (reason != null && reason.isNotBlank()) {
                commandData["reason"] = reason
            }

            sendWebSocketCommand("leave_room", leaveRequestId, commandData)

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Sent leave_room command for $roomId with requestId=$leaveRequestId"
                )
        }
    }

    fun joinRoomWithCallback(
        roomIdOrAlias: String,
        viaServers: List<String>,
        callback: (Pair<String?, String?>?) -> Unit
    ) {
        with(vm) {
            val requestId = requestIdCounter++
            joinRoomCallbacks[requestId] = callback

            val finalViaServers = (viaServers + "matrix.org").distinct()
            val dataMap = mutableMapOf<String, Any>("room_id_or_alias" to roomIdOrAlias)
            dataMap["via"] = finalViaServers
            sendWebSocketCommand("join_room", requestId, dataMap)
        }
    }
}
