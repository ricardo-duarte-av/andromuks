package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/**
 * Typing indicators and read receipts for [AppViewModel].
 */
internal class ReadReceiptsTypingCoordinator(private val vm: AppViewModel) {

    fun updateTypingUsers(roomId: String, userIds: List<String>) {
        with(vm) {
            if (roomId.isBlank()) {
                if (BuildConfig.DEBUG)
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Ignoring typing update with blank roomId"
                    )
                return
            }

            val isRoomInCache =
                RoomTimelineCache.isRoomOpened(roomId) || RoomTimelineCache.isRoomActivelyCached(roomId)

            if (!isRoomInCache) {
                return
            }

            typingUsersMap[roomId] = userIds

            if (currentRoomId == roomId) {
                typingUsers = userIds
            }

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Updated typing users for room $roomId: ${userIds.size} users typing (currentRoomId=$currentRoomId)"
                )
        }
    }

    fun getTypingUsersForRoom(roomId: String): List<String> {
        with(vm) {
            val allTypingUsers = typingUsersMap[roomId] ?: emptyList()
            return allTypingUsers.filter { it != currentUserId }
        }
    }

    fun populateReadReceiptsFromCache() {
        with(vm) {
            try {
                synchronized(readReceiptsLock) {
                    val cachedReceipts = ReadReceiptCache.getAllReceipts()
                    if (cachedReceipts.isNotEmpty()) {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: populateReadReceiptsFromCache called - current receipts: ${readReceipts.size} events, cache: ${cachedReceipts.size} events"
                            )

                        var hasChanges = false
                        cachedReceipts.forEach { (eventId, cachedReceiptsList) ->
                            if (cachedReceiptsList.isNotEmpty()) {
                                val existingReceipts = readReceipts[eventId]
                                if (existingReceipts == null || existingReceipts.isEmpty()) {
                                    readReceipts[eventId] = cachedReceiptsList.toMutableList()
                                    hasChanges = true
                                    if (BuildConfig.DEBUG)
                                        android.util.Log.d(
                                            "Andromuks",
                                            "AppViewModel: populateReadReceiptsFromCache - added ${cachedReceiptsList.size} receipts for eventId=$eventId from cache"
                                        )
                                } else {
                                    val existingUserIds = existingReceipts.map { it.userId }.toSet()
                                    val newReceipts =
                                        cachedReceiptsList.filter { it.userId !in existingUserIds }
                                    if (newReceipts.isNotEmpty()) {
                                        readReceipts[eventId] =
                                            (existingReceipts + newReceipts).toMutableList()
                                        hasChanges = true
                                        
                                        // (3) IMPLICIT DELIVERY: Receipt in cache implies delivery
                                        val targetMessageId = bridgeStatusEventToMessageId[eventId] ?: eventId
                                        if (messageBridgeSendStatus.containsKey(targetMessageId)) {
                                            updateBridgeStatus(targetMessageId, "delivered")
                                        }

                                        if (BuildConfig.DEBUG)
                                            android.util.Log.d(
                                                "Andromuks",
                                                "AppViewModel: populateReadReceiptsFromCache - merged ${newReceipts.size} new receipts for eventId=$eventId from cache (${readReceipts[eventId]?.size} total)"
                                            )
                                    }
                                }
                            }
                        }

                        if (hasChanges) {
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: populateReadReceiptsFromCache - merged cache with existing receipts, total events: ${readReceipts.size}"
                                )
                            readReceiptsUpdateCounter++
                        } else {
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: populateReadReceiptsFromCache - no changes (all cache receipts already in readReceipts)"
                                )
                        }
                    } else {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: populateReadReceiptsFromCache - cache is empty, no receipts to populate"
                            )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to populate readReceipts from cache", e)
            }
        }
    }

    fun markRoomAsRead(roomId: String, eventId: String) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: markRoomAsRead called with roomId: '$roomId', eventId: '$eventId'"
                )

            val lastSentEventId = lastMarkReadSent[roomId]
            if (lastSentEventId == eventId) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Skipping duplicate mark_read for room $roomId with event $eventId (already sent)"
                    )
                optimisticallyClearUnreadCounts(roomId)
                return
            }

            lastMarkReadSent[roomId] = eventId
            optimisticallyClearUnreadCounts(roomId)

            val result = markRoomAsReadInternal(roomId, eventId)

            if (result != WebSocketResult.SUCCESS) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: markRoomAsRead failed with result: $result - queuing for retry when connection is restored"
                )
                addPendingOperation(
                    AppViewModel.PendingWebSocketOperation(
                        type = "markRoomAsRead",
                        data =
                            mapOf(
                                "roomId" to roomId,
                                "eventId" to eventId
                            )
                    ),
                    saveToStorage = true
                )
            }
        }
    }

    fun markRoomAsReadFromNotification(roomId: String, eventId: String, onComplete: (() -> Unit)? = null) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: markRoomAsReadFromNotification called for room $roomId"
                )

            if (!isWebSocketConnected() || !spacesLoaded) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: WebSocket not ready yet, queueing notification action"
                    )

                pendingNotificationActions.add(
                    AppViewModel.PendingNotificationAction(
                        type = "mark_read",
                        roomId = roomId,
                        eventId = eventId,
                        onComplete = onComplete
                    )
                )

                if (onComplete != null) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: WebSocket not ready, calling completion callback immediately to prevent UI stalling"
                    )
                    onComplete()
                }
                return
            }

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Marking room as read from notification (WebSocket maintained by service)"
                )
            val markReadRequestId = requestIdCounter++

            markReadRequests[markReadRequestId] = roomId
            beginNotificationAction()
            val completionWrapper: () -> Unit = {
                onComplete?.invoke()
                endNotificationAction()
            }
            notificationActionCompletionCallbacks[markReadRequestId] = completionWrapper

            viewModelScope.launch(Dispatchers.IO) {
                val timeoutMs = if (isAppVisible) 30000L else 10000L
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Setting mark read timeout to ${timeoutMs}ms (app visible: $isAppVisible)"
                    )
                delay(timeoutMs)
                withContext(Dispatchers.Main) {
                    if (notificationActionCompletionCallbacks.containsKey(markReadRequestId)) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Mark read timeout after ${timeoutMs}ms for requestId=$markReadRequestId, calling completion callback"
                        )
                        notificationActionCompletionCallbacks.remove(markReadRequestId)?.invoke()
                        markReadRequests.remove(markReadRequestId)
                    }
                }
            }

            val commandData =
                mapOf(
                    "room_id" to roomId,
                    "event_id" to eventId,
                    "receipt_type" to "m.read"
                )

            val result = sendWebSocketCommand("mark_read", markReadRequestId, commandData)

            if (result != WebSocketResult.SUCCESS) {
                android.util.Log.e(
                    "Andromuks",
                    "AppViewModel: Failed to send mark read from notification, result: $result"
                )
                markReadRequests.remove(markReadRequestId)
                notificationActionCompletionCallbacks.remove(markReadRequestId)?.invoke()
                return
            }

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Mark read sent, WebSocket remains connected via service"
                )
        }
    }

    fun markRoomAsReadInternal(roomId: String, eventId: String): WebSocketResult {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: markRoomAsReadInternal called")
            val markReadRequestId = requestIdCounter++

            val commandData =
                mapOf(
                    "room_id" to roomId,
                    "event_id" to eventId,
                    "receipt_type" to "m.read"
                )

            val result = sendWebSocketCommand("mark_read", markReadRequestId, commandData)

            if (result == WebSocketResult.SUCCESS) {
                markReadRequests[markReadRequestId] = roomId
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Mark read queued with request_id: $markReadRequestId"
                    )
            } else {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to send mark read, result: $result")
            }

            return result
        }
    }

    fun optimisticallyClearUnreadCounts(roomId: String) {
        with(vm) {
            val existingRoom = roomMap[roomId]
            if (existingRoom != null &&
                ((existingRoom.unreadCount != null && existingRoom.unreadCount > 0) ||
                    (existingRoom.highlightCount != null && existingRoom.highlightCount > 0))
            ) {
                val updatedRoom =
                    existingRoom.copy(
                        unreadCount = null,
                        highlightCount = null
                    )
                roomMap[roomId] = updatedRoom

                val updatedAllRooms =
                    allRooms.map { room ->
                        if (room.id == roomId) updatedRoom else room
                    }
                allRooms = updatedAllRooms

                if (spaceList.isNotEmpty()) {
                    val updatedSpaces =
                        spaceList.map { space ->
                            val updatedSpaceRooms =
                                space.rooms.map { room ->
                                    if (room.id == roomId) updatedRoom else room
                                }
                            space.copy(rooms = updatedSpaceRooms)
                        }
                    setSpaces(updatedSpaces, skipCounterUpdate = false)
                }
            }

            invalidateRoomSectionCache()

            roomListUpdateCounter++
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: Marked room $roomId as read (in-memory only)")
        }
    }
}
