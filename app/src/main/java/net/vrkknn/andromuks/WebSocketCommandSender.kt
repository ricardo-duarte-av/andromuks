package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig
import org.json.JSONObject

/**
 * Outgoing WebSocket command pipeline for [AppViewModel]: raw send, init gating, offline queue,
 * and acknowledgment tracking. Keeps [AppViewModel] smaller; state lives on the ViewModel, this
 * class only orchestrates sends.
 */
internal class WebSocketCommandSender(
    private val vm: AppViewModel
) {

    /**
     * Low-level send (Map, JSONObject, or legacy direct JSON). Prefer [send] for normal commands.
     */
    fun sendRaw(command: String, requestId: Int, data: Any?): WebSocketResult {
        val dataMap = when (data) {
            null -> emptyMap<String, Any>()
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                data as Map<String, Any>
            }
            is JSONObject -> {
                val map = mutableMapOf<String, Any>()
                val keys = data.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = data.opt(key) ?: ""
                }
                map
            }
            else -> {
                android.util.Log.w(
                    "Andromuks",
                    "sendRawWebSocketCommand: Complex data type, using legacy path: ${data::class.simpleName}"
                )
                return try {
                    val json = JSONObject()
                    json.put("command", command)
                    json.put("request_id", requestId)
                    json.put("data", data)
                    val jsonString = json.toString()
                    val ws = WebSocketService.getWebSocket()
                    if (ws == null) {
                        android.util.Log.w("Andromuks", "AppViewModel: WebSocket is not connected, cannot send command: $command")
                        return WebSocketResult.NOT_CONNECTED
                    }
                    ws.send(jsonString)
                    WebSocketResult.SUCCESS
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Failed to send raw WebSocket command: $command", e)
                    WebSocketResult.CONNECTION_ERROR
                }
            }
        }

        val normalizedData = if (command == "send_to_device" && dataMap.isNotEmpty()) {
            val eventType = (dataMap["event_type"] as? String) ?: (dataMap["type"] as? String) ?: ""
            val normalized = mutableMapOf<String, Any>()
            if (eventType.isNotBlank()) {
                normalized["event_type"] = eventType
            }
            if (dataMap.containsKey("encrypted")) {
                normalized["encrypted"] = dataMap["encrypted"] ?: false
            }
            if (dataMap.containsKey("messages")) {
                normalized["messages"] = dataMap["messages"] ?: emptyList<Any>()
            }
            dataMap.forEach { (key, value) ->
                if (!normalized.containsKey(key)) {
                    normalized[key] = value
                }
            }
            normalized
        } else {
            dataMap
        }

        return if (WebSocketService.sendCommand(command, requestId, normalizedData)) {
            WebSocketResult.SUCCESS
        } else {
            WebSocketResult.CONNECTION_ERROR
        }
    }

    fun send(command: String, requestId: Int, data: Map<String, Any>): WebSocketResult {
        if (vm.isOfflineMode && !isOfflineCapableCommand(command)) {
            android.util.Log.w("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Command $command queued for offline retry")
            queueOfflineRetry(command, requestId, data)
            return WebSocketResult.NOT_CONNECTED
        }

        if (!WebSocketService.isWebSocketConnected()) {
            val isUserInitiated = when (command) {
                "send_message", "mark_read" -> true
                else -> false
            }

            if (isUserInitiated) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: WebSocket is not connected, queuing user-initiated command: $command (requestId: $requestId)"
                )
                queueOfflineRetry(command, requestId, data)
            } else {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: WebSocket is not connected, skipping automatic command: $command (will be re-requested when online)"
                    )
                }
            }
            return WebSocketResult.NOT_CONNECTED
        }

        val isInitialRoomStateLoading = vm.allRoomStatesRequested && !vm.allRoomStatesLoaded
        val isExemptCommand = command == "get_room_state" && isInitialRoomStateLoading

        if (!vm.canSendCommandsToBackend && !isExemptCommand) {
            synchronized(vm.pendingCommandsQueue) {
                vm.pendingCommandsQueue.add(Triple(command, requestId, data))
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Command $command (requestId: $requestId) queued - waiting for init_complete + sync_complete processing (${vm.pendingCommandsQueue.size} commands queued)"
                )
            }
            return WebSocketResult.NOT_CONNECTED
        }

        val roomId = data["room_id"] as? String
        if (command == "paginate" && BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "🟠 sendWebSocketCommand: SENDING paginate - requestId=$requestId, roomId=$roomId, data=${org.json.JSONObject(data).toString().take(200)}"
            )
        } else if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "sendWebSocketCommand: command='$command', requestId=$requestId, data=${org.json.JSONObject(data).toString().take(200)}"
            )
        }

        val sendResult = if (WebSocketService.sendCommand(command, requestId, data)) {
            if (command == "paginate" && BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "🟠 sendWebSocketCommand: paginate SENT successfully - requestId=$requestId, roomId=$roomId"
                )
            }
            WebSocketResult.SUCCESS
        } else {
            android.util.Log.w(
                "Andromuks",
                "🟠 sendWebSocketCommand: FAILED to send $command - requestId=$requestId, roomId=$roomId (service returned false)"
            )
            WebSocketResult.CONNECTION_ERROR
        }

        if (requestId > 0 && sendResult == WebSocketResult.SUCCESS) {
            val messageId = java.util.UUID.randomUUID().toString()
            val acknowledgmentTimeout = System.currentTimeMillis() + 30000L

            val operation = AppViewModel.PendingWebSocketOperation(
                type = "command_$command",
                data = mapOf(
                    "command" to command,
                    "requestId" to requestId,
                    "data" to data
                ),
                retryCount = 0,
                messageId = messageId,
                timestamp = System.currentTimeMillis(),
                acknowledged = false,
                acknowledgmentTimeout = acknowledgmentTimeout
            )

            synchronized(vm.pendingOperationsLock) {
                if (vm.pendingWebSocketOperations.size >= AppViewModel.MAX_QUEUE_SIZE) {
                    val oldest = vm.pendingWebSocketOperations.minByOrNull { it.timestamp }
                    if (oldest != null) {
                        vm.pendingWebSocketOperations.remove(oldest)
                        if (BuildConfig.DEBUG) {
                            android.util.Log.w(
                                "Andromuks",
                                "AppViewModel: Queue full (${AppViewModel.MAX_QUEUE_SIZE}), removed oldest operation: ${oldest.type}"
                            )
                        }
                    }
                }
                vm.pendingWebSocketOperations.add(operation)
            }
        }

        return sendResult
    }

    fun flushPendingQueue() {
        val commandsToFlush = synchronized(vm.pendingCommandsQueue) {
            val commands = vm.pendingCommandsQueue.toList()
            vm.pendingCommandsQueue.clear()
            commands
        }

        if (commandsToFlush.isNotEmpty()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Flushing ${commandsToFlush.size} queued commands after init_complete + sync_complete processing"
                )
            }
            for ((command, requestId, data) in commandsToFlush) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: Flushing queued command: $command (requestId: $requestId)")
                }
                send(command, requestId, data)
            }
        }

    }

    fun queueOfflineRetry(command: String, requestId: Int, data: Map<String, Any>) {
        val isUserInitiated = when (command) {
            "send_message" -> true
            "mark_read" -> true
            else -> false
        }

        if (!isUserInitiated) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Skipping offline storage for automatic command: $command (will be re-requested when online)"
                )
            }
            return
        }

        vm.addPendingOperation(
            AppViewModel.PendingWebSocketOperation(
                type = "offline_$command",
                data = mapOf(
                    "command" to command,
                    "requestId" to requestId,
                    "data" to data
                ),
                retryCount = 0
            ),
            saveToStorage = true
        )
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Queued offline command: $command (user-initiated)")
        }
    }

    private fun isOfflineCapableCommand(command: String): Boolean {
        return when (command) {
            "get_profile", "get_room_state" -> true
            else -> false
        }
    }
}
