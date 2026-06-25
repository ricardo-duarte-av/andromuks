package net.vrkknn.andromuks

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class RoomNavigationRequest(
    val roomId: String,
    val timestamp: Long?,
    val source: Source,
) {
    enum class Source { NOTIFICATION, SHORTCUT, BUBBLE, RESTORE }
}

/**
 * Pending/direct room navigation, highlight map, and cache-first open for [AppViewModel].
 */
internal class NavigationCoordinator(private val vm: AppViewModel) {

    private val _roomNavigationRequests = Channel<RoomNavigationRequest>(Channel.CONFLATED)
    val roomNavigationRequests: Flow<RoomNavigationRequest> = _roomNavigationRequests.receiveAsFlow()

    fun setPendingRoomNavigation(roomId: String, fromNotification: Boolean) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Set pending room navigation to: $roomId (fromNotification: $fromNotification)",
                )
            pendingRoomNavigation = roomId
            isPendingNavigationFromNotification = fromNotification
            _roomNavigationRequests.trySend(
                RoomNavigationRequest(roomId = roomId, timestamp = null, source = RoomNavigationRequest.Source.SHORTCUT)
            )
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
            if (!targetEventId.isNullOrBlank()) {
                this@NavigationCoordinator.setPendingHighlightEvent(roomId, targetEventId)
            }
            _roomNavigationRequests.trySend(
                RoomNavigationRequest(roomId = roomId, timestamp = notificationTimestamp, source = RoomNavigationRequest.Source.NOTIFICATION)
            )
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

                    // RACE CONDITION FIX: Mark notification navigation pending BEFORE suspending,
                    // so awaitRoomDataReadiness (already polling) blocks until data is ready.
                    isPendingNavigationFromNotification = true

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
                        // Clear the flag so the readiness poll doesn't block permanently
                        if (isPendingNavigationFromNotification) {
                            isPendingNavigationFromNotification = false
                        }
                        // If no room is actively loading (user navigated back to room list),
                        // reset isTimelineLoading so the spinner does not stick permanently.
                        if (currentRoomId.isEmpty()) {
                            isTimelineLoading = false
                        }
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

                val mustFetchFreshTimeline = needsFreshTimelinePaginate()
                if (mustFetchFreshTimeline) {
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Forcing fresh paginate - roomId=$roomId, forceAfterWsDown=$forceFreshPaginateAfterWsDown, wsConnected=${isWebSocketConnected()}",
                    )
                }

                // Require a full page of cached events before bypassing requestRoomTimeline.
                // Rooms with fewer than INITIAL_ROOM_PAGINATE_LIMIT events must go through
                // requestRoomTimeline so a background paginate is issued for the missing history.
                // A rich cache is always rendered instantly (no loading flash). If it was flagged
                // mightBeStale by an intentional WS drop, we render it AND fire a cheap freshness
                // probe below — instead of the old behaviour of skipping the fast path and doing a
                // foreground full paginate. needsFreshTimelinePaginate() no longer gates this branch.
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

                        // Abort if navigation was superseded — processCachedEvents mutates
                        // eventChainMap/editEventsMap synchronously and the expectedRoomId guard
                        // inside executeTimelineRebuild only discards the final state write.
                        if (currentRoomId != roomId) {
                            android.util.Log.d(
                                "Andromuks",
                                "🔵 navigateToRoomWithCache: Aborting cache-hit fast path — superseded by currentRoomId=$currentRoomId",
                            )
                            return@launch
                        }

                        RoomTimelineCache.markRoomAsCached(roomId)
                        RoomTimelineCache.markRoomAccessed(roomId)

                        roomOpenTimestamps[roomId] = System.currentTimeMillis()

                        // Freshness. While the WebSocket is up, SyncIngestor keeps every actively-
                        // cached room's timeline fresh (delta replay on unintentional reconnects too),
                        // so a non-stale cache hit needs no paginate and renders instantly below.
                        //
                        // An intentional drop (battery-saver standby) flags every cache mightBeStale.
                        // We do NOT render the cached tail in that case: a fresh *connect* (no
                        // last_received_id) makes the backend push the latest event per room, which
                        // gets appended to the cache top — so the cached tail may now sit BELOW a
                        // silent gap, and rendering it would show stale content we then have to wipe.
                        // Instead we keep the loading state and fire a cheap freshness probe
                        // (paginateViaExec, FRESHNESS_PROBE_LIMIT) anchored on the event frozen at the
                        // drop. handlePaginationMerge's freshness-probe fast path decides BEFORE any
                        // render: anchor present ⇒ contiguous, merge the few newer events and render;
                        // anchor absent ⇒ gap, purge and reseed with a full INITIAL_ROOM_PAGINATE_LIMIT
                        // window. Either way the user only ever sees the loading indicator then the
                        // final, correct timeline — never the stale tail.
                        if (RoomTimelineCache.isMightBeStale(roomId)) {
                            val anchorEventId = RoomTimelineCache.staleAnchorFor(roomId)
                            freshnessProbePendingEpoch[roomId] = RoomTimelineCache.staleEpochFor(roomId)
                            // isTimelineLoading was set true at navigation start; leave it true and
                            // blank any prior room's events so the user sees the loading state until
                            // the probe (or its reseed) builds the verified timeline — never the
                            // unverified cached tail. (This launch runs on the Main dispatcher, same
                            // as the cache-miss timelineEvents reset below.)
                            timelineEvents = emptyList()
                            android.util.Log.d(
                                "Andromuks",
                                "🔵 navigateToRoomWithCache: cache flagged might-be-stale — firing freshness probe (limit=${AppViewModel.FRESHNESS_PROBE_LIMIT}, anchor=$anchorEventId) for $roomId BEFORE rendering",
                            )
                            if (anchorEventId != null) {
                                paginateViaExec(
                                    roomId,
                                    maxTimelineId = 0L,
                                    limit = AppViewModel.FRESHNESS_PROBE_LIMIT,
                                    freshnessProbeAnchor = anchorEventId,
                                )
                            } else {
                                // No anchor (cache had no events at the drop) — nothing to verify
                                // against, so reseed directly with a full window.
                                paginateViaExec(
                                    roomId,
                                    maxTimelineId = 0L,
                                    limit = AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT,
                                )
                            }
                            return@launch
                        }

                        // Fresh cache: render instantly from cache (no loading flash).
                        processCachedEvents(
                            roomId,
                            RoomTimelineCache.getCachedEventsForTimeline(roomId),
                            openingFromNotification = notificationTimestamp != null,
                        )

                        if (!isWebSocketConnected()) {
                            // Not stale but WS momentarily down (unintentional blip): keep the prior
                            // behaviour of deferring a paginate to onInitComplete as a safety net.
                            roomsAwaitingInitCompletePaginate.add(roomId)
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
                } else if (notificationTimestamp != null && cachedEventCount > 0 && !mustFetchFreshTimeline) {
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Notification with partial cache ($cachedEventCount events) - showing immediately, will merge paginate in background",
                    )
                    val partialCachedEvents = RoomTimelineCache.getCachedEvents(roomId)
                    if (partialCachedEvents != null && partialCachedEvents.isNotEmpty()) {
                        // Abort if navigation was superseded — see note on the cache-hit branch above.
                        if (currentRoomId != roomId) {
                            android.util.Log.d(
                                "Andromuks",
                                "🔵 navigateToRoomWithCache: Aborting partial-cache notification merge — superseded by currentRoomId=$currentRoomId",
                            )
                            return@launch
                        }

                        processCachedEvents(
                            roomId,
                            RoomTimelineCache.getCachedEventsForTimeline(roomId),
                            openingFromNotification = true,
                        )
                        RoomTimelineCache.markRoomAsCached(roomId)
                        RoomTimelineCache.markRoomAccessed(roomId)
                        roomOpenTimestamps[roomId] = System.currentTimeMillis()

                        if (isWebSocketConnected() && AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT > 0) {
                            val paginateRequestId = WebSocketService.allocateRequestId()
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
                    // Catch-all fall-through to requestRoomTimeline. Reasons: cache below the
                    // $cacheSufficientThreshold fast-path threshold, OR a forced-fresh paginate is
                    // required (WS was down). Note: requestRoomTimeline still renders any cached
                    // window instantly and only sends a cheap freshness probe — this is not a full refetch.
                    android.util.Log.d(
                        "Andromuks",
                        "🔵 navigateToRoomWithCache: Not taking cache fast-path - roomId=$roomId, cachedEventCount=$cachedEventCount (need >= $cacheSufficientThreshold), mustFetchFresh=$mustFetchFreshTimeline — routing to requestRoomTimeline",
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
                    if (currentRoomId.isEmpty()) {
                        isTimelineLoading = false
                    }
                    return@launch
                }

                // Only blank the on-screen timeline when there is nothing cached to keep showing.
                // If a cached window exists, requestRoomTimeline renders it immediately and merges
                // a background paginate on top (handleBackgroundPrefetch), so clearing here would
                // just produce a one-frame "empty room" flash before the async rebuild swaps it
                // back in. The rebuild owns the swap; we leave the existing events visible.
                if (RoomTimelineCache.getCachedEventCount(roomId) == 0) {
                    timelineEvents = emptyList()
                }
                android.util.Log.d(
                    "Andromuks",
                    "🔵 navigateToRoomWithCache: Calling requestRoomTimeline - roomId=$roomId, cachedEvents=${RoomTimelineCache.getCachedEventCount(roomId)}, isTimelineLoading=$isTimelineLoading",
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
