package net.vrkknn.andromuks

import net.vrkknn.andromuks.utils.MatrixMxidUtils

/**
 * Client slash commands from the composer — see [AppViewModel.executeCommand].
 */
internal class SlashCommandsCoordinator(private val vm: AppViewModel) {

    fun executeCommand(
        roomId: String,
        text: String,
        context: android.content.Context,
        navController: androidx.navigation.NavController? = null,
    ): Boolean = with(vm) {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return false

        val parts = trimmed.split("\\s+".toRegex(), limit = 10)
        if (parts.isEmpty()) return false

        val command = parts[0].lowercase()
        val args = parts.drop(1)

        return when {
            command == "/join" || command == "/j" -> {
                if (args.isNotEmpty()) {
                    val roomRef = args[0]
                    val reason = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                    if (navController != null) {
                        android.util.Log.d("Andromuks", "AppViewModel: /join command - room: $roomRef, reason: $reason")
                    }
                }
                true
            }
            command == "/leave" || command == "/part" -> {
                leaveRoom(roomId)
                true
            }
            command == "/invite" -> {
                if (args.isNotEmpty()) {
                    val userId = args[0]
                    val reason = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                    val requestId = requestIdCounter++
                    sendWebSocketCommand("set_membership", requestId, mapOf(
                        "room_id" to roomId,
                        "user_id" to userId,
                        "action" to "invite",
                        "reason" to (reason ?: "")
                    ))
                }
                true
            }
            command == "/kick" -> {
                if (args.isNotEmpty()) {
                    val userId = args[0]
                    val reason = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                    val requestId = requestIdCounter++
                    sendWebSocketCommand("set_membership", requestId, mapOf(
                        "room_id" to roomId,
                        "user_id" to userId,
                        "action" to "kick",
                        "reason" to (reason ?: "")
                    ))
                }
                true
            }
            command == "/ban" -> {
                if (args.isNotEmpty()) {
                    val userId = args[0]
                    val reason = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                    val requestId = requestIdCounter++
                    sendWebSocketCommand("set_membership", requestId, mapOf(
                        "room_id" to roomId,
                        "user_id" to userId,
                        "action" to "ban",
                        "reason" to (reason ?: "")
                    ))
                }
                true
            }
            command == "/myroomnick" || command == "/roomnick" -> {
                if (args.isNotEmpty()) {
                    val name = args.joinToString(" ")
                    val requestId = requestIdCounter++
                    sendWebSocketCommand("set_state", requestId, mapOf(
                        "room_id" to roomId,
                        "type" to "m.room.member",
                        "state_key" to currentUserId,
                        "content" to mapOf(
                            "displayname" to name,
                            "membership" to "join"
                        )
                    ))
                }
                true
            }
            command == "/myroomavatar" -> {
                return false
            }
            command == "/globalnick" || command == "/globalname" -> {
                if (args.isNotEmpty()) {
                    val name = args.joinToString(" ")
                    val requestId = requestIdCounter++
                    sendWebSocketCommand("set_profile_field", requestId, mapOf(
                        "field" to "displayname",
                        "value" to name
                    ))
                }
                true
            }
            command == "/globalavatar" -> {
                return false
            }
            command == "/roomname" -> {
                if (args.isNotEmpty()) {
                    val name = args.joinToString(" ")
                    val requestId = requestIdCounter++
                    sendWebSocketCommand("set_state", requestId, mapOf(
                        "room_id" to roomId,
                        "type" to "m.room.name",
                        "state_key" to "",
                        "content" to mapOf("name" to name)
                    ))
                }
                true
            }
            command == "/roomavatar" -> {
                return false
            }
            command == "/redact" -> {
                if (args.isNotEmpty()) {
                    val eventId = args[0]
                    val reason = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
                    val requestId = requestIdCounter++
                    sendWebSocketCommand("redact_event", requestId, mapOf(
                        "room_id" to roomId,
                        "event_id" to eventId,
                        "reason" to (reason ?: "")
                    ))
                }
                true
            }
            command == "/raw" -> {
                if (args.isNotEmpty()) {
                    val eventType = args[0]
                    val jsonStr = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() } ?: "{}"
                    val requestId = requestIdCounter++
                    try {
                        val content = org.json.JSONObject(jsonStr)
                        val contentMap = mutableMapOf<String, Any>()
                        val keys = content.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            contentMap[key] = content.get(key)
                        }
                        sendWebSocketCommand("send_event", requestId, mapOf(
                            "room_id" to roomId,
                            "type" to eventType,
                            "content" to contentMap,
                            "disable_encryption" to false
                        ))
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Invalid JSON in /raw command", e)
                    }
                }
                true
            }
            command == "/unencryptedraw" -> {
                if (args.isNotEmpty()) {
                    val eventType = args[0]
                    val jsonStr = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() } ?: "{}"
                    val requestId = requestIdCounter++
                    try {
                        val content = org.json.JSONObject(jsonStr)
                        val contentMap = mutableMapOf<String, Any>()
                        val keys = content.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            contentMap[key] = content.get(key)
                        }
                        sendWebSocketCommand("send_event", requestId, mapOf(
                            "room_id" to roomId,
                            "type" to eventType,
                            "content" to contentMap,
                            "disable_encryption" to true
                        ))
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Invalid JSON in /unencryptedraw command", e)
                    }
                }
                true
            }
            command == "/rawstate" -> {
                if (args.size >= 2) {
                    val eventType = args[0]
                    val stateKey = args[1]
                    val jsonStr = args.drop(2).joinToString(" ").takeIf { it.isNotBlank() } ?: "{}"
                    val requestId = requestIdCounter++
                    try {
                        val content = org.json.JSONObject(jsonStr)
                        val contentMap = mutableMapOf<String, Any>()
                        val keys = content.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            contentMap[key] = content.get(key)
                        }
                        sendWebSocketCommand("set_state", requestId, mapOf(
                            "room_id" to roomId,
                            "type" to eventType,
                            "state_key" to stateKey,
                            "content" to contentMap
                        ))
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Invalid JSON in /rawstate command", e)
                    }
                }
                true
            }
            command == "/alias" -> {
                if (args.size >= 2) {
                    val action = args[0].lowercase()
                    val alias = args[1]
                    val requestId = requestIdCounter++
                    when (action) {
                        "add", "create" -> {
                            sendWebSocketCommand("set_state", requestId, mapOf(
                                "room_id" to roomId,
                                "type" to "m.room.canonical_alias",
                                "state_key" to "",
                                "content" to mapOf("alias" to alias)
                            ))
                        }
                        "del", "remove", "rm", "delete" -> {
                            sendWebSocketCommand("set_state", requestId, mapOf(
                                "room_id" to roomId,
                                "type" to "m.room.canonical_alias",
                                "state_key" to "",
                                "content" to mapOf("alias" to "")
                            ))
                        }
                    }
                }
                true
            }
            command == "/converttodm" -> {
                val remainder =
                    trimmed.split("\\s+".toRegex(), limit = 2).getOrNull(1)?.trim().orEmpty()
                val arg0 = remainder.takeIf { it.isNotBlank() }
                val targetUserId = if (arg0 != null) {
                    MatrixMxidUtils.parseFlexibleUserMxidInput(arg0)
                } else {
                    val inferred = accountDataCoordinator.inferSingleOtherMemberMxid(roomId)
                    if (inferred == null) {
                        android.widget.Toast.makeText(
                            context,
                            "Specify a user (@user:server) or use in a room with exactly one other member",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        return true
                    }
                    MatrixMxidUtils.normalizeMxid(inferred)
                }
                if (!targetUserId.contains(":") || targetUserId.length < 4) {
                    android.widget.Toast.makeText(
                        context,
                        "Invalid Matrix user ID",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
                val map = accountDataCoordinator.mutableDirectMapFromCurrent()
                accountDataCoordinator.removeRoomFromAllDirectEntries(map, roomId)
                val list = map.getOrPut(targetUserId) { mutableListOf() }
                if (roomId !in list) list.add(roomId)
                accountDataCoordinator.commitMDirectToServer(map)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: /converttodm — room=$roomId with $targetUserId")
                }
                true
            }
            command == "/converttoroom" -> {
                val map = accountDataCoordinator.mutableDirectMapFromCurrent()
                val wasDirect = map.values.any { it.contains(roomId) }
                if (!wasDirect) {
                    val msg = if (roomMap[roomId]?.isDirectMessage == true) {
                        "This room is not in your m.direct map here, so it can't be removed that way. " +
                            "The Direct tab may be using room metadata (dm_user_id), not account m.direct."
                    } else {
                        "This room is not in your m.direct list."
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    return true
                }
                accountDataCoordinator.removeRoomFromAllDirectEntries(map, roomId)
                accountDataCoordinator.commitMDirectToServer(map)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: /converttoroom — removed $roomId from m.direct")
                }
                true
            }
            else -> false
        }
    }
}
