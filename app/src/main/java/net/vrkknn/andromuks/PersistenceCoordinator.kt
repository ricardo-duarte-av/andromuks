package net.vrkknn.andromuks

import android.content.Context
import androidx.lifecycle.viewModelScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.ConcurrentModificationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SharedPreferences state, pending WebSocket op queue, and retry — for [AppViewModel].
 */
internal class PersistenceCoordinator(private val vm: AppViewModel) {

    fun addPendingOperation(operation: AppViewModel.PendingWebSocketOperation, saveToStorage: Boolean = false): Boolean = with(vm) {
        synchronized(pendingOperationsLock) {
            if (pendingWebSocketOperations.size >= AppViewModel.MAX_QUEUE_SIZE) {
                val oldest = pendingWebSocketOperations.minByOrNull { it.timestamp }
                if (oldest != null) {
                    pendingWebSocketOperations.remove(oldest)
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Queue full (${AppViewModel.MAX_QUEUE_SIZE}), removed oldest operation: ${oldest.type}"
                    )
                }
            }

            pendingWebSocketOperations.add(operation)
        }
        if (saveToStorage) {
            savePendingOperationsToStorage()
        }
        true
    }

    internal fun savePendingOperationsToStorage() = with(vm) {
        appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                val operationsArray = JSONArray()

                val operationsSnapshot = synchronized(pendingOperationsLock) {
                    pendingWebSocketOperations.toList()
                }

                operationsSnapshot.forEach { operation ->
                    val operationJson = JSONObject(operation.toJsonMap())
                    operationsArray.put(operationJson)
                }

                prefs.edit()
                    .putString("pending_websocket_operations", operationsArray.toString())
                    .apply()

            } catch (e: ConcurrentModificationException) {
                android.util.Log.w("Andromuks", "AppViewModel: ConcurrentModificationException in savePendingOperationsToStorage, retrying", e)
                try {
                    val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                    val operationsArray = JSONArray()
                    val operationsSnapshot = synchronized(pendingOperationsLock) {
                        pendingWebSocketOperations.toList()
                    }
                    operationsSnapshot.forEach { operation ->
                        val operationJson = JSONObject(operation.toJsonMap())
                        operationsArray.put(operationJson)
                    }
                    prefs.edit()
                        .putString("pending_websocket_operations", operationsArray.toString())
                        .apply()
                } catch (e2: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Failed to save pending WebSocket operations to storage (retry also failed)", e2)
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to save pending WebSocket operations to storage", e)
            }
        }
    }

    fun loadPendingOperationsFromStorage() = with(vm) {
        appContext?.let { context ->
            try {
                val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                val operationsJson = prefs.getString("pending_websocket_operations", null)

                if (operationsJson == null || operationsJson.isEmpty()) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending WebSocket operations found in storage")
                    return@with
                }

                val operationsArray = JSONArray(operationsJson)
                val loadedOperations = mutableListOf<AppViewModel.PendingWebSocketOperation>()
                val currentTime = System.currentTimeMillis()

                for (i in 0 until operationsArray.length()) {
                    val operationJson = operationsArray.getJSONObject(i)
                    val operationMap = mutableMapOf<String, Any>()

                    val keys = operationJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        operationMap[key] = operationJson.get(key)
                    }

                    val operation = AppViewModel.PendingWebSocketOperation.fromJsonMap(operationMap)
                    if (operation != null) {
                        val age = currentTime - operation.timestamp
                        if (age <= AppViewModel.MAX_MESSAGE_AGE_MS) {
                            loadedOperations.add(operation)
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed old pending operation (age: ${age / 1000 / 60} minutes): ${operation.type}")
                        }
                    }
                }

                val operationsToLoad = if (loadedOperations.size > AppViewModel.MAX_QUEUE_SIZE) {
                    loadedOperations.sortedByDescending { it.timestamp }.take(AppViewModel.MAX_QUEUE_SIZE)
                } else {
                    loadedOperations
                }

                synchronized(pendingOperationsLock) {
                    pendingWebSocketOperations.clear()
                    pendingWebSocketOperations.addAll(operationsToLoad)
                }

                if (operationsToLoad.isNotEmpty()) {
                    android.util.Log.i("Andromuks", "AppViewModel: Loaded ${operationsToLoad.size} pending WebSocket operations from storage (removed ${loadedOperations.size - operationsToLoad.size} old/over-limit)")
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No valid pending WebSocket operations to load")
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to load pending WebSocket operations from storage", e)
            }
        }
    }

    internal fun flushPendingQueueAfterReconnection() = with(vm) {
        val pendingSize = synchronized(pendingOperationsLock) { pendingWebSocketOperations.size }
        if (pendingSize == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending operations to retry after reconnection")
            return@with
        }

        val unacknowledgedCount = synchronized(pendingOperationsLock) {
            pendingWebSocketOperations.count { !it.acknowledged }
        }
        if (unacknowledgedCount == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: All pending operations already acknowledged, no retry needed")
            return@with
        }

        android.util.Log.i("Andromuks", "AppViewModel: Scheduling retry of $unacknowledgedCount unacknowledged operations after stabilization delay")
        lastReconnectionTime = System.currentTimeMillis()

        vm.viewModelScope.launch {
            delay(300L)

            android.util.Log.i("Andromuks", "AppViewModel: Starting retry of pending operations (new commands can be sent immediately)")
            retryPendingWebSocketOperations(bypassTimeout = true)

            delay(500L)

            val remaining = synchronized(pendingOperationsLock) { pendingWebSocketOperations.size }
            android.util.Log.i("Andromuks", "AppViewModel: Retry complete, $remaining operations remain in queue")
        }
    }

    internal fun retryPendingWebSocketOperations(bypassTimeout: Boolean = false) = with(vm) {
        // Guard: if the backend isn't ready yet, sendWebSocketCommand would queue the command in
        // pendingCommandsQueue and return NOT_CONNECTED, which would re-add the op here too —
        // double-queuing the same message. Wait until flushPendingQueue() signals readiness.
        if (!canSendCommandsToBackend) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Deferring offline ops retry — canSendCommandsToBackend=false, will retry from flushPendingQueue()")
            return@with
        }

        val pendingSize = synchronized(pendingOperationsLock) { pendingWebSocketOperations.size }
        if (pendingSize == 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No pending WebSocket operations to retry")
            return@with
        }

        val currentTime = System.currentTimeMillis()

        // Select AND remove in one synchronized block to prevent two concurrent callers from both
        // selecting the same ops before either has had a chance to remove them (which would send
        // each offline message twice and crash the LazyColumn with duplicate keys).
        val operationsToRetry = synchronized(pendingOperationsLock) {
            val selected = pendingWebSocketOperations
                .filter { op ->
                    if (op.acknowledged) return@filter false
                    // bypassTimeout only applies to offline-queued ops; command_* ops represent
                    // in-flight sends waiting for server ack and must respect their timeout to
                    // avoid re-sending a message that the server already received.
                    val effectiveBypass = bypassTimeout && !op.type.startsWith("command_")
                    effectiveBypass || currentTime >= op.acknowledgmentTimeout
                }
                .sortedBy { it.timestamp }
                .take(10)
            // Atomically claim the selected ops so no other concurrent call can pick them up.
            selected.forEach { pendingWebSocketOperations.remove(it) }
            selected
        }

        if (operationsToRetry.isEmpty()) {
            val (acknowledgedCount, waitingCount, total) = synchronized(pendingOperationsLock) {
                val ack = pendingWebSocketOperations.count { it.acknowledged }
                val waiting = pendingWebSocketOperations.count { currentTime < it.acknowledgmentTimeout }
                Triple(ack, waiting, pendingWebSocketOperations.size)
            }
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: No operations ready to retry (acknowledged: $acknowledgedCount, waiting for timeout: $waitingCount, total: $total)"
            )
            return@with
        }

        android.util.Log.i("Andromuks", "AppViewModel: PHASE 5.4 - Retrying ${operationsToRetry.size} pending WebSocket operations (oldest first, batch limited to 10)")
        savePendingOperationsToStorage()

        vm.viewModelScope.launch {
            operationsToRetry.forEachIndexed { index, operation ->
                try {
                    if (index > 0) {
                        delay(100L)
                    }

                    when (operation.type) {
                        "sendMessage" -> {
                            // Legacy op type — no longer created. sendMessage failures are now
                            // handled by offline_send_message (WebSocket down) or pendingCommandsQueue
                            // (!canSendCommandsToBackend). Retrying via sendMessageInternal here would
                            // create a new echo with a new transaction_id, duplicating the message.
                            android.util.Log.w("Andromuks", "AppViewModel: Dropping legacy sendMessage op (type retired, message already in offline queue)")
                        }
                        "sendReply" -> {
                            android.util.Log.w("Andromuks", "AppViewModel: Skipping retry of sendReply operation (complex to serialize)")
                        }
                        "markRoomAsRead" -> {
                            val roomId = operation.data["roomId"] as String?
                            val eventId = operation.data["eventId"] as String?
                            if (roomId != null && eventId != null) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrying markRoomAsRead for room $roomId (attempt ${operation.retryCount + 1})")
                                readReceiptsTypingCoordinator.markRoomAsReadInternal(roomId, eventId)
                            }
                        }
                        else -> {
                            if (operation.type.startsWith("offline_")) {
                                val command = operation.data["command"] as String?
                                val requestId = operation.data["requestId"] as Int?
                                @Suppress("UNCHECKED_CAST")
                                val data = operation.data["data"] as? Map<String, Any>

                                if (command != null && requestId != null && data != null) {
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: NETWORK OPTIMIZATION - Retrying offline command: $command (attempt ${operation.retryCount + 1})")
                                    val newRequestId = requestIdCounter++
                                    val result = sendWebSocketCommand(command, newRequestId, data)
                                    if (result != WebSocketResult.SUCCESS && operation.retryCount < maxRetryAttempts) {
                                        this@PersistenceCoordinator.addPendingOperation(operation.copy(retryCount = operation.retryCount + 1), saveToStorage = true)
                                    }
                                }
                            } else if (operation.type.startsWith("command_")) {
                                val command = operation.type.removePrefix("command_")
                                val requestId = operation.data["requestId"] as? Int
                                @Suppress("UNCHECKED_CAST")
                                val data = operation.data["data"] as? Map<String, Any>

                                if (requestId != null && data != null) {
                                    if (command == "mark_read") {
                                        val roomId = data["room_id"] as? String
                                        val eventId = data["event_id"] as? String
                                        if (roomId != null && eventId != null) {
                                            val lastSent = lastMarkReadSent[roomId]
                                            if (lastSent == eventId) {
                                                android.util.Log.w(
                                                    "Andromuks",
                                                    "AppViewModel: Skipping retry for duplicate mark_read for room $roomId event $eventId"
                                                )
                                                return@forEachIndexed
                                            }
                                            lastMarkReadSent[roomId] = eventId
                                        }
                                    }
                                    val newRequestId = requestIdCounter++
                                    android.util.Log.w("Andromuks", "AppViewModel: Retrying command '$command' with new request_id: $newRequestId (was: $requestId, attempt ${operation.retryCount + 1})")
                                    sendWebSocketCommand(command, newRequestId, data)
                                }
                            } else {
                                android.util.Log.w("Andromuks", "AppViewModel: Unknown operation type for retry: ${operation.type}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Andromuks", "AppViewModel: Error retrying operation ${operation.type}: ${e.message}")
                    if (operation.retryCount < maxRetryAttempts) {
                        this@PersistenceCoordinator.addPendingOperation(operation.copy(retryCount = operation.retryCount + 1), saveToStorage = true)
                    }
                }
            }

            savePendingOperationsToStorage()

            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.4 - Finished retrying ${operationsToRetry.size} operations, ${pendingWebSocketOperations.size} remain queued")
        }
    }

    fun checkAcknowledgmentTimeouts() = with(vm) {
        val currentTime = System.currentTimeMillis()
        val operationsToRetry = mutableListOf<AppViewModel.PendingWebSocketOperation>()
        val operationsToRemove = mutableListOf<AppViewModel.PendingWebSocketOperation>()
        val operationsSnapshot = synchronized(pendingOperationsLock) { pendingWebSocketOperations.toList() }

        val stabilizationPeriodMs = 5000L
        val timeSinceReconnection = if (lastReconnectionTime > 0) currentTime - lastReconnectionTime else Long.MAX_VALUE
        val isInStabilizationPeriod = timeSinceReconnection < stabilizationPeriodMs

        operationsSnapshot.forEach { operation ->
            if (!operation.acknowledged && currentTime >= operation.acknowledgmentTimeout) {
                if (isInStabilizationPeriod && operation.retryCount == 0) {
                    val newTimeout = currentTime + 10000L
                    android.util.Log.i("Andromuks", "AppViewModel: QUEUE FLUSHING - Extending timeout for ${operation.type} (stabilization period, ${timeSinceReconnection}ms since reconnection)")
                    val updatedOperation = operation.copy(acknowledgmentTimeout = newTimeout)
                    operationsToRemove.add(operation)
                    operationsToRetry.add(updatedOperation)
                } else if (operation.retryCount < maxRetryAttempts) {
                    val newRetryCount = operation.retryCount + 1
                    val newTimeout = currentTime + 30000L
                    android.util.Log.w("Andromuks", "AppViewModel: Message acknowledgment timeout - retrying (attempt $newRetryCount/${maxRetryAttempts}): ${operation.type}, messageId: ${operation.messageId}")

                    operationsToRetry.add(operation.copy(
                        retryCount = newRetryCount,
                        acknowledgmentTimeout = newTimeout
                    ))
                    operationsToRemove.add(operation)
                } else {
                    android.util.Log.e("Andromuks", "AppViewModel: Message failed after ${maxRetryAttempts} retries: ${operation.type}, messageId: ${operation.messageId}")
                    logActivity("Message Failed - ${operation.type} (${maxRetryAttempts} retries)", null)
                    operationsToRemove.add(operation)
                }
            }
        }

        if (operationsToRemove.isNotEmpty()) {
            synchronized(pendingOperationsLock) {
                operationsToRemove.forEach { pendingWebSocketOperations.remove(it) }
            }
        }

        // offline_* ops are re-added only on send failure (checked in the send loop below)
        operationsToRetry
            .filterNot { it.type.startsWith("command_") || it.type.startsWith("offline_") }
            .forEach { this@PersistenceCoordinator.addPendingOperation(it, saveToStorage = true) }

        if (operationsToRetry.isNotEmpty()) {
            android.util.Log.i("Andromuks", "AppViewModel: Retrying ${operationsToRetry.size} timed-out messages")
            operationsToRetry.forEach { operation ->
                when {
                    operation.type == "sendMessage" -> {
                        // Legacy op type — dropped to avoid duplicate sends (see MessageSendCoordinator).
                        android.util.Log.w("Andromuks", "AppViewModel: Dropping legacy sendMessage op from timeout check")
                    }
                    operation.type == "sendReply" -> {
                        android.util.Log.w("Andromuks", "AppViewModel: Skipping retry of sendReply (originalEvent not stored)")
                    }
                    operation.type == "markRoomAsRead" -> {
                        val roomId = operation.data["roomId"] as? String
                        val eventId = operation.data["eventId"] as? String
                        if (roomId != null && eventId != null) {
                            readReceiptsTypingCoordinator.markRoomAsReadInternal(roomId, eventId)
                        }
                    }
                    operation.type.startsWith("offline_") -> {
                        val command = operation.data["command"] as? String
                        val requestId = operation.data["requestId"] as? Int
                        @Suppress("UNCHECKED_CAST")
                        val data = operation.data["data"] as? Map<String, Any>

                        if (command != null && requestId != null && data != null) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Retrying offline command: $command (attempt ${operation.retryCount + 1})")
                            val newRequestId = requestIdCounter++
                            val result = sendWebSocketCommand(command, newRequestId, data)
                            if (result != WebSocketResult.SUCCESS && operation.retryCount < maxRetryAttempts) {
                                this@PersistenceCoordinator.addPendingOperation(operation, saveToStorage = true)
                            }
                        } else {
                            android.util.Log.w("Andromuks", "AppViewModel: Invalid offline operation data - command: $command, requestId: $requestId, data: ${data != null}")
                        }
                    }
                    operation.type.startsWith("command_") -> {
                        val command = operation.type.removePrefix("command_")
                        val requestId = operation.data["requestId"] as? Int
                        @Suppress("UNCHECKED_CAST")
                        val data = operation.data["data"] as? Map<String, Any>
                        if (requestId != null && data != null) {
                            if (command == "mark_read") {
                                val roomId = data["room_id"] as? String
                                val eventId = data["event_id"] as? String
                                if (roomId != null && eventId != null) {
                                    val lastSent = lastMarkReadSent[roomId]
                                    if (lastSent == eventId) {
                                        android.util.Log.w(
                                            "Andromuks",
                                            "AppViewModel: Skipping retry for duplicate mark_read for room $roomId event $eventId"
                                        )
                                        return@forEach
                                    }
                                    lastMarkReadSent[roomId] = eventId
                                }
                            }
                            val newRequestId = requestIdCounter++
                            android.util.Log.w("Andromuks", "AppViewModel: Retrying command '$command' with new request_id: $newRequestId (was: $requestId)")
                            sendWebSocketCommand(command, newRequestId, data)
                        }
                    }
                    else -> {
                        android.util.Log.w("Andromuks", "AppViewModel: Unknown operation type for retry: ${operation.type}")
                    }
                }
            }
        }
    }

    internal fun cleanupAcknowledgedMessages() = with(vm) {
        val currentTime = System.currentTimeMillis()
        val oneHourAgo = currentTime - (60 * 60 * 1000L)

        val operationsToRemove = synchronized(pendingOperationsLock) {
            pendingWebSocketOperations.filter {
                it.acknowledged && it.timestamp < oneHourAgo
            }
        }

        if (operationsToRemove.isNotEmpty()) {
            synchronized(pendingOperationsLock) {
                operationsToRemove.forEach { pendingWebSocketOperations.remove(it) }
            }
            savePendingOperationsToStorage()
            android.util.Log.i("Andromuks", "AppViewModel: PHASE 5.4 - Cleaned up ${operationsToRemove.size} acknowledged messages older than 1 hour")
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.4 - No acknowledged messages to clean up")
        }
    }

    fun loadStateFromStorage(context: Context) {
        vm.diagnosticsCoordinator.loadActivityLogFromStorage(context)
    }

    internal fun handleMessageAcknowledgmentByRequestId(requestId: Int) = with(vm) {
        val operation: AppViewModel.PendingWebSocketOperation? = synchronized(pendingOperationsLock) {
            pendingWebSocketOperations.find { op ->
                (op.data["requestId"] as? Int) == requestId
            }
        }
        if (operation != null) {
            val command = operation.data["command"] as? String ?: operation.type.removePrefix("command_")
            logActivity("Command Acknowledged - $command (request_id: $requestId)", null)
            synchronized(pendingOperationsLock) {
                pendingWebSocketOperations.remove(operation)
            }
            savePendingOperationsToStorage()
        } else {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: PHASE 5.3 - No pending operation found for requestId: $requestId (may have been already acknowledged, not tracked, or request_id=0)")
        }
    }
}
