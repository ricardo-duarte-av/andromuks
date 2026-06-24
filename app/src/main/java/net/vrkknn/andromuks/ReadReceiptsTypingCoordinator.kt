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
                    val roomIds = ReadReceiptCache.getRoomIds()
                    if (roomIds.isEmpty()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - cache is empty")
                        return@synchronized
                    }

                    var hasChanges = false

                    roomIds.forEach { roomId ->
                        val cachedForRoom = ReadReceiptCache.getForRoom(roomId)
                        if (cachedForRoom.isEmpty()) return@forEach

                        val roomReceiptsMap = readReceipts.getOrPut(roomId) { mutableMapOf() }
                        val roomUserIndex = readReceiptsIndex.getOrPut(roomId) { mutableMapOf() }

                        cachedForRoom.forEach { (eventId, cachedList) ->
                            if (cachedList.isEmpty()) return@forEach
                            val existing = roomReceiptsMap[eventId]
                            if (existing == null || existing.isEmpty()) {
                                roomReceiptsMap[eventId] = cachedList.toMutableList()
                                cachedList.forEach { r -> roomUserIndex[r.userId] = eventId }
                                hasChanges = true
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - added ${cachedList.size} receipts for $eventId in $roomId")
                            } else {
                                val existingUserIds = existing.map { it.userId }.toSet()
                                val newReceipts = cachedList.filter { it.userId !in existingUserIds }
                                if (newReceipts.isNotEmpty()) {
                                    // evict these users from any other event in this room (index lookup)
                                    newReceipts.forEach { r ->
                                        val oldEventId = roomUserIndex[r.userId]
                                        if (oldEventId != null && oldEventId != eventId) {
                                            val oldList = roomReceiptsMap[oldEventId]
                                            oldList?.removeAll { it.userId == r.userId }
                                            if (oldList != null && oldList.isEmpty()) roomReceiptsMap.remove(oldEventId)
                                        }
                                        roomUserIndex[r.userId] = eventId
                                    }
                                    roomReceiptsMap[eventId] = (existing + newReceipts).toMutableList()
                                    hasChanges = true

                                    val targetMessageId = bridgeStatusEventToMessageId[eventId] ?: eventId
                                    if (messageBridgeSendStatus.containsKey(targetMessageId)) {
                                        updateBridgeStatus(targetMessageId, "delivered")
                                    }
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - merged ${newReceipts.size} receipts for $eventId in $roomId")
                                }
                            }
                        }
                    }

                    if (hasChanges) readReceiptsUpdateCounter++
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateReadReceiptsFromCache - done, hasChanges=$hasChanges, rooms=${readReceipts.size}")
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

            // Don't advance the read marker while the app is backgrounded. currentRoomId is kept
            // in memory across background (for fast resume — see onAppBecameInvisible) and the
            // WebSocket stays live in always-on mode, so both sync_complete processing AND the
            // paginate-response handler (triggered by FCM cache hydration, paginateViaExec) keep
            // flowing through here for the previously-open room. Auto-marking it read makes the
            // backend emit a `dismiss` push that wipes the notification we just posted from FCM.
            // A visible bubble is the exception — it is genuinely user-facing. Notification actions
            // (Mark-read button / reply) go through markRoomAsReadFromNotification, which bypasses
            // this path entirely and is intentionally NOT gated.
            if (!isAppVisible && !BubbleTracker.isBubbleVisible(roomId)) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Skipping mark_read for $roomId — app backgrounded and no visible bubble"
                    )
                return
            }

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

            // When public read receipts are disabled we still advance the marker, but privately.
            // The backend's mark_read always moves m.fully_read regardless of receipt type, so a
            // private receipt clears our unread state (and the "new messages" divider) without
            // leaking a visible receipt to other users. Suppressing entirely would freeze
            // m.fully_read forever — see resolveSendReadReceipts().
            val receiptType = if (resolveSendReadReceipts(roomId)) "m.read" else "m.read.private"

            val result = markRoomAsReadInternal(roomId, eventId, receiptType)

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
            // If the caller didn't supply an event_id (e.g. old notification or reply update),
            // fall back to the latest known event for this room so the receipt is meaningful.
            val resolvedEventId = eventId.ifBlank {
                RoomListCache.getLatestEventId(roomId) ?: eventId
            }
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: markRoomAsReadFromNotification called for room $roomId, eventId=$resolvedEventId (raw=$eventId)"
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
                        eventId = resolvedEventId,
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

            // Public receipts off → still advance m.fully_read with a private receipt (see the
            // note in markRoomAsRead). Marking read from a notification should clear unread state
            // either way; only the visibility of the receipt to others is gated.
            val receiptType = if (resolveSendReadReceipts(roomId)) "m.read" else "m.read.private"

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Marking room as read from notification (WebSocket maintained by service)"
                )
            val markReadRequestId = WebSocketService.allocateRequestId()

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
                    "event_id" to resolvedEventId,
                    "receipt_type" to receiptType
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

    fun markRoomAsReadInternal(roomId: String, eventId: String, receiptType: String = "m.read"): WebSocketResult {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: markRoomAsReadInternal called")
            val markReadRequestId = WebSocketService.allocateRequestId()

            val commandData =
                mapOf(
                    "room_id" to roomId,
                    "event_id" to eventId,
                    "receipt_type" to receiptType
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
