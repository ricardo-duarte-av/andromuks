package net.vrkknn.andromuks

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pending/direct room navigation, highlight map, and cache-first open for [AppViewModel].
 */
internal class NavigationCoordinator(private val vm: AppViewModel) {

    fun setPendingRoomNavigation(roomId: String, fromNotification: Boolean) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Set pending room navigation to: $roomId (fromNotification: $fromNotification)",
                )
            pendingRoomNavigation = roomId
            isPendingNavigationFromNotification = fromNotification
        }
    }

    fun setDirectRoomNavigation(
        roomId: String,
        notificationTimestamp: Long?,
        targetEventId: String?,
    ) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: OPTIMIZATION #1 - Set direct room navigation to: $roomId (timestamp: $notificationTimestamp, targetEventId: $targetEventId)",
                )
            directRoomNavigation = roomId
            directRoomNavigationTimestamp = notificationTimestamp
            directRoomNavigationTrigger++
            // Prevent onAppBecameVisible from restoring the previously open room on top of this
            // explicit navigation request — the target room takes priority.
            pendingRoomToRestore = null
            if (!targetEventId.isNullOrBlank()) {
                this@NavigationCoordinator.setPendingHighlightEvent(roomId, targetEventId)
            }
        }
    }

    fun getDirectRoomNavigation(): String? {
        with(vm) {
            val roomId = directRoomNavigation
            if (roomId != null && BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: OPTIMIZATION #1 - Getting direct room navigation: $roomId",
                )
            return roomId
        }
    }

    fun clearDirectRoomNavigation() {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: OPTIMIZATION #1 - Clearing direct room navigation",
                )
            directRoomNavigation = null
            directRoomNavigationTimestamp = null
        }
    }

    fun getDirectRoomNavigationTimestamp(): Long? = vm.directRoomNavigationTimestamp

    fun getPendingRoomNavigation(): String? {
        with(vm) {
            val roomId = pendingRoomNavigation
            if (roomId != null && BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Getting pending room navigation: $roomId",
                )
            return roomId
        }
    }

    fun clearPendingRoomNavigation() {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: Clearing pending room navigation")
            pendingRoomNavigation = null
            isPendingNavigationFromNotification = false
        }
    }

    fun setPendingHighlightEvent(roomId: String, eventId: String?) {
        with(vm) {
            if (eventId.isNullOrBlank()) return
            pendingHighlightEvents[roomId] = eventId
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Stored pending highlight event for $roomId -> $eventId",
                )
        }
    }

    fun consumePendingHighlightEvent(roomId: String): String? {
        with(vm) {
            val eventId = pendingHighlightEvents.remove(roomId)
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: consumePendingHighlightEvent($roomId) -> ${eventId ?: "NULL"} (pendingHighlightEvents keys: ${pendingHighlightEvents.keys})",
                )
            }
            return eventId
        }
    }

    fun navigateToRoomWithCache(roomId: String, notificationTimestamp: Long?) {
        with(vm) {
            android.util.Log.d(
                "Andromuks",
                "🔵 navigateToRoomWithCache: START - roomId=$roomId, notificationTimestamp=$notificationTimestamp, isProcessingPendingItems=$isProcessingPendingItems, initialSyncComplete=$initialSyncComplete",
            )
            updateCurrentRoomIdInPrefs(roomId)
            RoomTimelineCache.addOpenedRoom(roomId)

            isTimelineLoading = true

            viewModelScope.launch {
                if (notificationTimestamp != null) {
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Opening from notification - flushing batched sync_complete messages first",
                    )

                    setCurrentRoomIdForTimeline(roomId)

                    RoomTimelineCache.markRoomAsCached(roomId)
                    RoomTimelineCache.addOpenedRoom(roomId)
                    if (BuildConfig.DEBUG) {
                        val cachedRoomIds = RoomTimelineCache.getActivelyCachedRoomIds()
                        android.util.Log.d(
                            "Andromuks",
                            "🔵 navigateToRoomWithCache: Set currentRoomId=$roomId and marked as actively cached before batch flush (activelyCachedRooms: ${cachedRoomIds.size} rooms, includes target: ${cachedRoomIds.contains(roomId)})",
                        )
                    }

                    val flushJob = syncBatchProcessor.forceFlushBatch()
                    flushJob?.join()

                    delay(100)

                    // Abort if a concurrent navigation superseded this one while we were suspended.
                    if (currentRoomId != roomId) {
                        android.util.Log.d(
                            "Andromuks",
                            "🔵 navigateToRoomWithCache: Aborting after flush — superseded by currentRoomId=$currentRoomId",
                        )
                        return@launch
                    }

                    val cacheAfterFlush = RoomTimelineCache.getCachedEventCount(roomId)
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Batch flush completed - cache now has $cacheAfterFlush events (may have been updated)",
                    )

                    appContext?.let { ctx ->
                        val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                        val preemptiveRooms =
                            prefs.getStringSet("preemptive_paginate_rooms", null)?.toMutableSet()
                        if (preemptiveRooms != null && preemptiveRooms.remove(roomId)) {
                            prefs.edit().putStringSet("preemptive_paginate_rooms", preemptiveRooms).apply()
                            android.util.Log.d(
                                "Andromuks",
                                "🔵 navigateToRoomWithCache: Cleared $roomId from preemptive_paginate_rooms SharedPreferences",
                            )
                        }
                    }
                }

                var cachedEventCount = RoomTimelineCache.getCachedEventCount(roomId)
                android.util.Log.d(
                    "Andromuks",
                    "🔵 navigateToRoomWithCache: Cache check - roomId=$roomId, cachedEventCount=$cachedEventCount, isActivelyCached=${RoomTimelineCache.isRoomActivelyCached(roomId)}",
                )

                // Require a full page of cached events before bypassing requestRoomTimeline.
                // Rooms with fewer than INITIAL_ROOM_PAGINATE_LIMIT events must go through
                // requestRoomTimeline so a background paginate is issued for the missing history.
                val cacheSufficientThreshold = AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT
                if (cachedEventCount >= cacheSufficientThreshold) {
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Using cache (>=${cacheSufficientThreshold} events) - roomId=$roomId, cachedEventCount=$cachedEventCount",
                    )
                    val cachedEvents = RoomTimelineCache.getCachedEvents(roomId)

                    if (cachedEvents != null) {
                        android.util.Log.d(
                            "Andromuks",
                            "🔵 navigateToRoomWithCache: Cache hit - roomId=$roomId, cachedEvents.size=${cachedEvents.size}, building timeline from cache",
                        )

                        processCachedEvents(
                            roomId,
                            RoomTimelineCache.getCachedEventsForTimeline(roomId),
                            openingFromNotification = notificationTimestamp != null,
                        )

                        RoomTimelineCache.markRoomAsCached(roomId)
                        RoomTimelineCache.markRoomAccessed(roomId)

                        roomOpenTimestamps[roomId] = System.currentTimeMillis()

                        // Background paginate to pull any newer events the cache may be missing
                        // (e.g. after a reconnect with clear_state=true that evicted activelyCachedRooms).
                        // Mirrors the same paginate sent by requestRoomTimeline's cache-hit path.
                        val wasAdded = roomsWithPendingPaginate.add(roomId)
                        if (wasAdded && isWebSocketConnected()) {
                            val paginateRequestId = requestIdCounter++
                            timelineRequests[paginateRequestId] = roomId
                            val result = sendWebSocketCommand(
                                "paginate",
                                paginateRequestId,
                                mapOf(
                                    "room_id" to roomId,
                                    "max_timeline_id" to 0,
                                    "limit" to AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT,
                                    "reset" to false,
                                ),
                            )
                            if (result != WebSocketResult.SUCCESS && !isWebSocketConnected()) {
                                timelineRequests.remove(paginateRequestId)
                                roomsWithPendingPaginate.remove(roomId)
                            }
                            android.util.Log.d(
                                "Andromuks",
                                "🔵 navigateToRoomWithCache: Sent background paginate for newer events - roomId=$roomId, reqId=$paginateRequestId, result=$result",
                            )
                        } else if (!wasAdded) {
                            android.util.Log.d(
                                "Andromuks",
                                "🔵 navigateToRoomWithCache: Skipping background paginate - roomId=$roomId already has pending paginate",
                            )
                        }

                        android.util.Log.d(
                            "Andromuks",
                            "🔵 navigateToRoomWithCache: SUCCESS - roomId=$roomId, timeline built from cache (${cachedEvents.size} events), isTimelineLoading=$isTimelineLoading",
                        )
                        return@launch
                    } else {
                        android.util.Log.w(
                            "Andromuks",
                            "🔵 navigateToRoomWithCache: Cache miss - roomId=$roomId, cachedEventCount=$cachedEventCount but getCachedEvents returned null",
                        )
                    }
                } else if (notificationTimestamp != null && cachedEventCount > 0) {
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Notification with partial cache ($cachedEventCount events) - showing immediately, will merge paginate in background",
                    )
                    val partialCachedEvents = RoomTimelineCache.getCachedEvents(roomId)
                    if (partialCachedEvents != null && partialCachedEvents.isNotEmpty()) {
                        processCachedEvents(
                            roomId,
                            RoomTimelineCache.getCachedEventsForTimeline(roomId),
                            openingFromNotification = true,
                        )
                        RoomTimelineCache.markRoomAsCached(roomId)
                        RoomTimelineCache.markRoomAccessed(roomId)
                        roomOpenTimestamps[roomId] = System.currentTimeMillis()

                        if (isWebSocketConnected() && AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT > 0) {
                            val paginateRequestId = requestIdCounter++
                            backgroundPrefetchRequests[paginateRequestId] = roomId
                            roomsWithPendingPaginate.add(roomId)
                            markInitialPaginate(roomId, "notification_background_merge")
                            val result =
                                sendWebSocketCommand(
                                    "paginate",
                                    paginateRequestId,
                                    mapOf(
                                        "room_id" to roomId,
                                        "max_timeline_id" to 0,
                                        "limit" to AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT,
                                        "reset" to false,
                                    ),
                                )
                            if (result == WebSocketResult.SUCCESS) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "🔵 navigateToRoomWithCache: Background merge paginate sent - showing ${partialCachedEvents.size} cached events immediately, history will merge seamlessly (reqId=$paginateRequestId)",
                                )
                            } else if (!isWebSocketConnected()) {
                                // Truly not connected — drop tracking.
                                backgroundPrefetchRequests.remove(paginateRequestId)
                                roomsWithPendingPaginate.remove(roomId)
                                android.util.Log.w(
                                    "Andromuks",
                                    "🔵 navigateToRoomWithCache: Failed to send background merge paginate for $roomId (not connected): $result",
                                )
                            } else {
                                // Queued (canSendCommandsToBackend=false) — keep tracking so the
                                // response is handled when flushPendingQueue() re-sends after init.
                                android.util.Log.d(
                                    "Andromuks",
                                    "🔵 navigateToRoomWithCache: Background merge paginate queued for $roomId (reqId=$paginateRequestId) — keeping tracking",
                                )
                            }
                        }

                        android.util.Log.d(
                            "Andromuks",
                            "🔵 navigateToRoomWithCache: SUCCESS (notification background merge) - roomId=$roomId, showing ${partialCachedEvents.size} events, paginate in flight",
                        )
                        return@launch
                    }
                    android.util.Log.w(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Partial cache count=$cachedEventCount but getCachedEvents returned null, falling through",
                    )
                } else {
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Cache insufficient - roomId=$roomId, cachedEventCount=$cachedEventCount (<50), will request timeline",
                    )
                }

                if (isProcessingPendingItems) {
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Waiting for pending items - roomId=$roomId, isProcessingPendingItems=true",
                    )
                    val waitStart = System.currentTimeMillis()
                    withTimeoutOrNull(10_000L) {
                        while (isProcessingPendingItems) {
                            delay(100L)
                        }
                    }
                    val waitDuration = System.currentTimeMillis() - waitStart
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Pending items finished - roomId=$roomId, waitDuration=${waitDuration}ms, isProcessingPendingItems=$isProcessingPendingItems",
                    )
                }

                // Abort if navigation was superseded during the pending-items wait.
                if (currentRoomId != roomId) {
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Aborting before requestRoomTimeline — superseded by currentRoomId=$currentRoomId",
                    )
                    return@launch
                }

                timelineEvents = emptyList()
                android.util.Log.d(
                    "Andromuks",
                    "🔵 navigateToRoomWithCache: Calling requestRoomTimeline (cleared timelineEvents to force fresh paginate) - roomId=$roomId, isTimelineLoading=$isTimelineLoading",
                )
                requestRoomTimeline(roomId)
                android.util.Log.d(
                    "Andromuks",
                    "🔵 navigateToRoomWithCache: requestRoomTimeline returned - roomId=$roomId, isTimelineLoading=$isTimelineLoading",
                )
            }
        }
    }
}
