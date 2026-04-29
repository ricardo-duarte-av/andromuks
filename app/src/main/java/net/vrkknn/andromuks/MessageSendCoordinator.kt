package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Outgoing message sending for [AppViewModel]: plain text, replies, edits, media, typing,
 * notification FIFO, and thread replies. WebSocket transport uses [AppViewModel.sendWebSocketCommand].
 *
 * Reactions remain in [ReactionCoordinator]; account-data emoji sync stays on the ViewModel.
 */
internal class MessageSendCoordinator(
    private val vm: AppViewModel
) {

    private val lastTypingSent = mutableMapOf<String, Long>()
    private val typingSendIntervalMs = 3000L

    // --- send_message variants ---

    fun sendMessage(
        roomId: String,
        text: String,
        mentions: List<String> = emptyList(),
        urlPreviews: JSONArray = JSONArray()
    ) {
        notifyUserSentTo(roomId)
        val reqId = vm.getAndIncrementRequestId()

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendMessage called with roomId=$roomId, text='$text', reqId=$reqId"
            )
        }

        vm.trackOutgoingRequest(reqId, roomId)

        val mentionsData = mapOf(
            "user_ids" to mentions,
            "room" to false
        )
        val urlPreviewsList = mutableListOf<Any>()
        for (i in 0 until urlPreviews.length()) {
            urlPreviews.optJSONObject(i)?.let { urlPreviewsList.add(it) }
        }
        vm.sendWebSocketCommand(
            "send_message",
            reqId,
            mapOf(
                "room_id" to roomId,
                "text" to text,
                "mentions" to mentionsData,
                "url_previews" to urlPreviewsList
            )
        )
    }

    fun sendMessage(roomId: String, text: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: sendMessage called with roomId: '$roomId', text: '$text'")
        }

        notifyUserSentTo(roomId)
        val result = sendMessageInternal(roomId, text)

        // Offline/not-ready cases are already covered:
        //   - WebSocket fully down → queueOfflineRetry stored it as offline_send_message
        //   - canSendCommandsToBackend=false → pendingCommandsQueue holds it for flushPendingQueue
        // Adding a sendMessage op here on top of those would produce a duplicate send on reconnect
        // (offline_send_message retries via sendWebSocketCommand while sendMessage retries via
        // sendMessageInternal, which creates a new echo with a new transaction_id → two server events).
        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: sendMessage not sent immediately (result: $result) — will be retried via offline queue or pendingCommandsQueue"
            )
        }
    }

    fun sendMessage(roomId: String, text: String, urlPreviews: JSONArray) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: sendMessage called with roomId: '$roomId', text: '$text', urlPreviews=${urlPreviews.length()}")
        }

        notifyUserSentTo(roomId)
        val result = sendMessageInternal(roomId, text, urlPreviews)

        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: sendMessage not sent immediately (result: $result) — will be retried via offline queue or pendingCommandsQueue"
            )
        }
    }

    internal fun sendMessageInternal(roomId: String, text: String, urlPreviews: JSONArray = JSONArray()): WebSocketResult {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendMessageInternal called")
        val messageRequestId = vm.getAndIncrementRequestId()

        val urlPreviewsList = mutableListOf<Any>()
        for (i in 0 until urlPreviews.length()) {
            urlPreviews.optJSONObject(i)?.let { urlPreviewsList.add(it) }
        }

        val result = vm.sendWebSocketCommand(
            "send_message",
            messageRequestId,
            mapOf(
                "room_id" to roomId,
                "text" to text,
                "mentions" to mapOf(
                    "user_ids" to emptyList<String>(),
                    "room" to false
                ),
                "url_previews" to urlPreviewsList
            )
        )

        if (result == WebSocketResult.SUCCESS) {
            vm.messageRequests[messageRequestId] = roomId
            vm.pendingSendCount++
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Message send queued with request_id: $messageRequestId"
                )
            }
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send message, result: $result")
        }

        return result
    }

    fun sendTyping(roomId: String) {
        val currentTime = System.currentTimeMillis()
        val lastSent = lastTypingSent[roomId] ?: 0L
        if (currentTime - lastSent < typingSendIntervalMs) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Typing indicator rate limited for room: $roomId (last sent ${currentTime - lastSent}ms ago)"
                )
            }
            return
        }

        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Sending typing indicator for room: $roomId")
        val typingRequestId = vm.getAndIncrementRequestId()
        val result = vm.sendWebSocketCommand(
            "set_typing",
            typingRequestId,
            mapOf(
                "room_id" to roomId,
                "timeout" to 10000
            )
        )

        if (result == WebSocketResult.SUCCESS) {
            lastTypingSent[roomId] = currentTime
        } else {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Typing indicator failed (result: $result), skipping")
            }
        }
    }

    fun sendMessageFromNotification(roomId: String, text: String, onComplete: (() -> Unit)? = null) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendMessageFromNotification called for room $roomId, text: '$text'"
            )
        }

        notifyUserSentTo(roomId)
        val now = System.currentTimeMillis()

        synchronized(vm.pendingNotificationMessagesLock) {
            val pendingMessage = AppViewModel.PendingNotificationMessage(
                roomId = roomId,
                text = text,
                timestamp = now,
                onComplete = onComplete
            )
            vm.pendingNotificationMessages.add(pendingMessage)
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Added message to FIFO buffer (queue size: ${vm.pendingNotificationMessages.size})"
                )
            }
        }

        if (vm.isWebSocketHealthy()) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: WebSocket is healthy, processing message immediately")
            processNextPendingNotificationMessage()
        } else {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: WebSocket is unhealthy, message stored in buffer (will process when healthy)"
                )
            }
            onComplete?.invoke()
        }
    }

    private fun processNextPendingNotificationMessage() {
        if (!vm.isWebSocketHealthy()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: WebSocket not healthy, skipping message processing")
            }
            return
        }

        val message = synchronized(vm.pendingNotificationMessagesLock) {
            if (vm.pendingNotificationMessages.isEmpty()) {
                null
            } else {
                vm.pendingNotificationMessages.removeAt(0)
            }
        }

        if (message == null) {
            return
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Processing pending notification message (roomId: ${message.roomId}, text: '${message.text}', queue size: ${vm.pendingNotificationMessages.size})"
            )
        }

        val messageRequestId = vm.getAndIncrementRequestId()
        vm.messageRequests[messageRequestId] = message.roomId
        vm.pendingSendCount++
        vm.beginNotificationAction()

        val completionWrapper: () -> Unit = {
            message.onComplete?.invoke()
            vm.endNotificationAction()
        }
        vm.notificationActionCompletionCallbacks[messageRequestId] = completionWrapper

        vm.viewModelScope.launch(Dispatchers.IO) {
            val timeoutMs = if (vm.isAppVisible) 30000L else 10000L
            delay(timeoutMs)
            withContext(Dispatchers.Main) {
                if (vm.notificationActionCompletionCallbacks.containsKey(messageRequestId)) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Message send timeout after ${timeoutMs}ms for requestId=$messageRequestId"
                    )
                    vm.notificationActionCompletionCallbacks.remove(messageRequestId)?.invoke()
                    vm.messageRequests.remove(messageRequestId)
                    if (vm.pendingSendCount > 0) {
                        vm.pendingSendCount--
                    }
                }
            }
        }

        val commandData = mapOf(
            "room_id" to message.roomId,
            "text" to message.text,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )

        val result = vm.sendWebSocketCommand("send_message", messageRequestId, commandData)

        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send pending notification message, result: $result")

            if (result == WebSocketResult.NOT_CONNECTED) {
                synchronized(vm.pendingNotificationMessagesLock) {
                    vm.pendingNotificationMessages.add(0, message)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Re-added message to FIFO buffer for retry (queue size: ${vm.pendingNotificationMessages.size})"
                        )
                    }
                }
            }

            vm.messageRequests.remove(messageRequestId)
            if (vm.pendingSendCount > 0) {
                vm.pendingSendCount--
            }
            vm.notificationActionCompletionCallbacks.remove(messageRequestId)?.invoke()
            return
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Message sent successfully, processing next in buffer if any")
        }

        if (vm.isWebSocketHealthy()) {
            processNextPendingNotificationMessage()
        }
    }

    fun processPendingNotificationMessages() {
        if (!vm.isWebSocketHealthy()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: WebSocket not healthy, skipping pending notification messages processing"
                )
            }
            return
        }

        val messageCount = synchronized(vm.pendingNotificationMessagesLock) {
            vm.pendingNotificationMessages.size
        }

        if (messageCount == 0) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: No pending notification messages to process")
            }
            return
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Processing $messageCount pending notification messages from FIFO buffer"
            )
        }

        vm.viewModelScope.launch(Dispatchers.IO) {
            var processed = 0
            while (true) {
                if (!vm.isWebSocketHealthy()) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: WebSocket became unhealthy during processing, stopping (processed $processed/$messageCount)"
                        )
                    }
                    break
                }

                val hasMore = synchronized(vm.pendingNotificationMessagesLock) {
                    vm.pendingNotificationMessages.isNotEmpty()
                }

                if (!hasMore) {
                    break
                }

                withContext(Dispatchers.Main) {
                    processNextPendingNotificationMessage()
                }

                processed++

                if (processed < messageCount) {
                    delay(500L)
                }
            }

            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Finished processing pending notification messages ($processed/$messageCount processed)"
                )
            }
        }
    }

    fun sendReply(roomId: String, text: String, originalEvent: TimelineEvent) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendReply called with roomId: '$roomId', text: '$text', originalEvent: ${originalEvent.eventId}"
            )
        }

        notifyUserSentTo(roomId)
        val result = sendReplyInternal(roomId, text, originalEvent)

        if (result != WebSocketResult.SUCCESS) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: sendReply failed with result: $result - health monitor will handle reconnection"
            )
        }
    }

    private fun sendReplyInternal(
        roomId: String,
        text: String,
        originalEvent: TimelineEvent
    ): WebSocketResult {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: sendReplyInternal called")
        val messageRequestId = vm.getAndIncrementRequestId()

        val mentions = mutableListOf<String>()
        if (originalEvent.sender.isNotBlank()) {
            mentions.add(originalEvent.sender)
        }

        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to mapOf(
                "m.in_reply_to" to mapOf(
                    "event_id" to originalEvent.eventId
                )
            ),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )

        val result = vm.sendWebSocketCommand("send_message", messageRequestId, commandData)

        if (result == WebSocketResult.SUCCESS) {
            vm.messageRequests[messageRequestId] = roomId
            vm.pendingSendCount++
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Reply send queued with request_id: $messageRequestId")
            }
        } else {
            android.util.Log.w("Andromuks", "AppViewModel: Failed to send reply, result: $result")
        }

        return result
    }

    fun sendEdit(roomId: String, text: String, originalEvent: TimelineEvent) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendEdit called with roomId: '$roomId', text: '$text', originalEvent: ${originalEvent.eventId}"
            )
        }

        if (WebSocketService.getWebSocket() == null) return
        val editRequestId = vm.getAndIncrementRequestId()

        vm.messageRequests[editRequestId] = roomId
        vm.pendingSendCount++

        val replyInfo = originalEvent.getReplyInfo()

        val relatesTo = mutableMapOf<String, Any>(
            "rel_type" to "m.replace",
            "event_id" to originalEvent.eventId
        )

        if (replyInfo != null) {
            relatesTo["m.in_reply_to"] = mapOf(
                "event_id" to replyInfo.eventId
            )
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Preserving reply relationship to event: ${replyInfo.eventId} when editing ${originalEvent.eventId}"
                )
            }
        }

        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to relatesTo,
            "mentions" to mapOf(
                "user_ids" to emptyList<String>(),
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with edit data: $commandData")
        }
        vm.sendWebSocketCommand("send_message", editRequestId, commandData)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Edit command sent with request_id: $editRequestId")
        }
    }

    fun sendMediaMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        mimeType: String,
        width: Int,
        height: Int,
        size: Long,
        blurHash: String,
        caption: String = "",
        msgType: String = "m.image",
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList(),
        thumbnailUrl: String? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null,
        thumbnailMimeType: String? = null,
        thumbnailSize: Long? = null
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendMediaMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', thumbnailUrl: '$thumbnailUrl'"
            )
        }

        if (WebSocketService.getWebSocket() == null) return
        notifyUserSentTo(roomId)
        val messageRequestId = vm.getAndIncrementRequestId()

        vm.messageRequests[messageRequestId] = roomId
        vm.pendingSendCount++

        val body = caption.ifBlank { filename }

        val infoMap = mutableMapOf<String, Any>(
            "mimetype" to mimeType,
            "xyz.amorgan.blurhash" to blurHash,
            "w" to width,
            "h" to height,
            "size" to size
        )

        if (thumbnailUrl != null) {
            infoMap["thumbnail_url"] = thumbnailUrl
            val thumbnailInfo = mutableMapOf<String, Any>()
            thumbnailWidth?.let { thumbnailInfo["w"] = it }
            thumbnailHeight?.let { thumbnailInfo["h"] = it }
            thumbnailMimeType?.let { thumbnailInfo["mimetype"] = it }
            if (thumbnailInfo.isNotEmpty()) {
                infoMap["thumbnail_info"] = thumbnailInfo
            }
        }

        val baseContent = mapOf(
            "msgtype" to msgType,
            "body" to body,
            "url" to mxcUrl,
            "info" to infoMap,
            "filename" to filename
        )

        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to "",
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: vm.getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            commandData["relates_to"] = relatesTo
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with media data: $commandData")
        }
        vm.sendWebSocketCommand("send_message", messageRequestId, commandData)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Media message command sent with request_id: $messageRequestId")
        }
    }

    fun sendImageMessage(
        roomId: String,
        mxcUrl: String,
        width: Int,
        height: Int,
        size: Long,
        mimeType: String,
        blurHash: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList(),
        thumbnailUrl: String? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null,
        thumbnailMimeType: String? = null,
        thumbnailSize: Long? = null
    ) {
        val filename = mxcUrl.substringAfterLast("/").let { mediaId ->
            val extension = when {
                mimeType.startsWith("image/jpeg") -> "jpg"
                mimeType.startsWith("image/png") -> "png"
                mimeType.startsWith("image/gif") -> "gif"
                mimeType.startsWith("image/webp") -> "webp"
                else -> "jpg"
            }
            "image_$mediaId.$extension"
        }

        sendMediaMessage(
            roomId = roomId,
            mxcUrl = mxcUrl,
            filename = filename,
            mimeType = mimeType,
            width = width,
            height = height,
            size = size,
            blurHash = blurHash,
            caption = caption ?: "",
            msgType = "m.image",
            threadRootEventId = threadRootEventId,
            replyToEventId = replyToEventId,
            isThreadFallback = isThreadFallback,
            mentions = mentions,
            thumbnailUrl = thumbnailUrl,
            thumbnailWidth = thumbnailWidth,
            thumbnailHeight = thumbnailHeight,
            thumbnailMimeType = thumbnailMimeType,
            thumbnailSize = thumbnailSize
        )
    }

    fun sendStickerMessage(
        roomId: String,
        mxcUrl: String,
        body: String,
        mimeType: String,
        size: Long,
        width: Int = 0,
        height: Int = 0,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendStickerMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', body: '$body', width: $width, height: $height"
            )
        }

        notifyUserSentTo(roomId)
        val messageRequestId = vm.getAndIncrementRequestId()

        vm.trackOutgoingRequest(messageRequestId, roomId)

        val baseContent = mutableMapOf<String, Any>(
            "msgtype" to "m.sticker",
            "body" to body,
            "url" to mxcUrl
        )
        val info = mutableMapOf<String, Any>(
            "mimetype" to mimeType,
            "size" to size
        )
        if (width > 0 && height > 0) {
            info["w"] = width
            info["h"] = height
        }
        baseContent["info"] = info

        val mentionsData = mapOf(
            "user_ids" to mentions,
            "room" to false
        )

        val dataMap = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "text" to "",
            "base_content" to baseContent,
            "mentions" to mentionsData,
            "url_previews" to emptyList<Any>()
        )

        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: vm.getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            dataMap["relates_to"] = relatesTo
        }

        vm.sendWebSocketCommand("send_message", messageRequestId, dataMap)

        vm.messageRequests[messageRequestId] = roomId
        vm.pendingSendCount++
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Sticker message send queued with request_id: $messageRequestId")
        }
    }

    fun sendVideoMessage(
        roomId: String,
        videoMxcUrl: String,
        thumbnailMxcUrl: String,
        width: Int,
        height: Int,
        duration: Int,
        size: Long,
        mimeType: String,
        thumbnailBlurHash: String,
        thumbnailWidth: Int,
        thumbnailHeight: Int,
        thumbnailSize: Long,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendVideoMessage called with roomId: '$roomId', videoMxcUrl: '$videoMxcUrl'"
            )
        }

        notifyUserSentTo(roomId)
        val messageRequestId = vm.getAndIncrementRequestId()

        vm.messageRequests[messageRequestId] = roomId
        vm.pendingSendCount++

        val filename = videoMxcUrl.substringAfterLast("/").let { mediaId ->
            val extension = when {
                mimeType.startsWith("video/mp4") -> "mp4"
                mimeType.startsWith("video/quicktime") -> "mov"
                mimeType.startsWith("video/webm") -> "webm"
                else -> "mp4"
            }
            "video_$mediaId.$extension"
        }

        val body = caption?.takeIf { it.isNotBlank() } ?: filename

        val baseContent = mapOf(
            "msgtype" to "m.video",
            "body" to body,
            "url" to videoMxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "thumbnail_info" to mapOf(
                    "mimetype" to "image/jpeg",
                    "xyz.amorgan.blurhash" to thumbnailBlurHash,
                    "w" to thumbnailWidth,
                    "h" to thumbnailHeight,
                    "size" to thumbnailSize
                ),
                "thumbnail_url" to thumbnailMxcUrl,
                "w" to width,
                "h" to height,
                "duration" to duration,
                "size" to size
            ),
            "filename" to filename
        )

        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: vm.getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            commandData["relates_to"] = relatesTo
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with video data: $commandData")
        }
        vm.sendWebSocketCommand("send_message", messageRequestId, commandData)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Video message command sent with request_id: $messageRequestId")
        }
    }

    fun sendDelete(roomId: String, originalEvent: TimelineEvent, reason: String = "") {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendDelete called with roomId: '$roomId', eventId: ${originalEvent.eventId}, reason: '$reason'"
            )
        }

        val deleteRequestId = vm.getAndIncrementRequestId()

        vm.reactionRequests[deleteRequestId] = roomId

        val commandData = mutableMapOf(
            "room_id" to roomId,
            "event_id" to originalEvent.eventId
        )

        if (reason.isNotBlank()) {
            commandData["reason"] = reason
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: redact_event with data: $commandData")
        }
        vm.sendWebSocketCommand("redact_event", deleteRequestId, commandData)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Delete command sent with request_id: $deleteRequestId")
        }
    }

    fun sendAudioMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        duration: Int,
        size: Long,
        mimeType: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendAudioMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', duration: ${duration}ms"
            )
        }

        notifyUserSentTo(roomId)
        val messageRequestId = vm.getAndIncrementRequestId()

        vm.messageRequests[messageRequestId] = roomId
        vm.pendingSendCount++

        val baseContent = mapOf(
            "msgtype" to "m.audio",
            "body" to filename,
            "url" to mxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "duration" to duration,
                "size" to size
            ),
            "filename" to filename
        )

        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: vm.getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            commandData["relates_to"] = relatesTo
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with audio data: $commandData")
        }
        vm.sendWebSocketCommand("send_message", messageRequestId, commandData)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Audio message command sent with request_id: $messageRequestId")
        }
    }

    fun sendFileMessage(
        roomId: String,
        mxcUrl: String,
        filename: String,
        size: Long,
        mimeType: String,
        caption: String? = null,
        threadRootEventId: String? = null,
        replyToEventId: String? = null,
        isThreadFallback: Boolean = true,
        mentions: List<String> = emptyList()
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendFileMessage called with roomId: '$roomId', mxcUrl: '$mxcUrl', filename: '$filename'"
            )
        }

        if (WebSocketService.getWebSocket() == null) return
        notifyUserSentTo(roomId)
        val messageRequestId = vm.getAndIncrementRequestId()

        vm.messageRequests[messageRequestId] = roomId
        vm.pendingSendCount++

        val baseContent = mapOf(
            "msgtype" to "m.file",
            "body" to filename,
            "url" to mxcUrl,
            "info" to mapOf(
                "mimetype" to mimeType,
                "size" to size
            ),
            "filename" to filename
        )

        val commandData = mutableMapOf<String, Any>(
            "room_id" to roomId,
            "base_content" to baseContent,
            "text" to (caption ?: ""),
            "mentions" to mapOf(
                "user_ids" to mentions,
                "room" to false
            ),
            "url_previews" to emptyList<String>()
        )
        if (threadRootEventId != null) {
            val resolvedReplyTarget = replyToEventId
                ?: vm.getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId
            val threadFallbackFlag = resolvedReplyTarget == null
            val relatesTo = mutableMapOf<String, Any>(
                "rel_type" to "m.thread",
                "event_id" to threadRootEventId,
                "is_falling_back" to threadFallbackFlag
            )
            if (resolvedReplyTarget != null) {
                relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
            }
            commandData["relates_to"] = relatesTo
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: About to send WebSocket command: send_message with file data: $commandData")
        }
        vm.sendWebSocketCommand("send_message", messageRequestId, commandData)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: File message command sent with request_id: $messageRequestId")
        }
    }

    fun sendThreadReply(
        roomId: String,
        text: String,
        threadRootEventId: String,
        fallbackReplyToEventId: String? = null,
        urlPreviews: JSONArray = JSONArray()
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendThreadReply called - roomId: $roomId, text: '$text', threadRoot: $threadRootEventId, fallbackReply: $fallbackReplyToEventId"
            )
        }

        if (!vm.isWebSocketConnected()) {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: WebSocket not connected - cannot send thread reply, health monitor will handle reconnection"
            )
            return
        }

        val messageRequestId = vm.getAndIncrementRequestId()
        vm.messageRequests[messageRequestId] = roomId
        vm.pendingSendCount++

        val resolvedReplyTarget = fallbackReplyToEventId
            ?: vm.getThreadMessages(roomId, threadRootEventId).lastOrNull()?.eventId

        val mentionUserIds = if (fallbackReplyToEventId != null) {
            vm.timelineEvents.firstOrNull { it.eventId == fallbackReplyToEventId }?.sender
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                ?: emptyList()
        } else {
            emptyList()
        }

        val isFallingBack = fallbackReplyToEventId == null

        val relatesTo = mutableMapOf<String, Any>(
            "rel_type" to "m.thread",
            "event_id" to threadRootEventId,
            "is_falling_back" to isFallingBack
        )

        if (resolvedReplyTarget != null) {
            relatesTo["m.in_reply_to"] = mapOf("event_id" to resolvedReplyTarget)
        }

        val urlPreviewsList = mutableListOf<Any>()
        for (i in 0 until urlPreviews.length()) {
            urlPreviews.optJSONObject(i)?.let { urlPreviewsList.add(it) }
        }
        val commandData = mapOf(
            "room_id" to roomId,
            "text" to text,
            "relates_to" to relatesTo,
            "mentions" to mapOf(
                "user_ids" to mentionUserIds,
                "room" to false
            ),
            "url_previews" to urlPreviewsList
        )

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Sending thread reply with data: $commandData")
        }
        vm.sendWebSocketCommand("send_message", messageRequestId, commandData)
        notifyUserSentTo(roomId)
    }

    private fun notifyUserSentTo(roomId: String) {
        val room = vm.getRoomById(roomId) ?: return
        vm.conversationsApi?.onUserSentToRoom(room)
    }
}
