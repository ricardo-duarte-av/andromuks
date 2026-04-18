package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Timeline & cache orchestration for [AppViewModel]: LRU save/restore, room timeline requests,
 * paginate / background prefetch handling, merge, and sync batch flush.
 *
 * Mutable UI state lives on [AppViewModel]; this class holds orchestration only. Method bodies use
 * [with] on the ViewModel so internal timeline APIs stay on [AppViewModel].
 */
internal class TimelineCacheCoordinator(private val vm: AppViewModel) {
    fun clearTimelineCache() {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Clearing timeline cache (${timelineEvents.size} events, ${eventChainMap.size} chain entries)",
                )
            timelineEvents = emptyList()
            eventChainMap.clear()
            editEventsMap.clear()
            // Note: We don't clear messageReactions as those are keyed by eventId and can be reused
        }
    }

    fun saveToLruCache(roomId: String) {
        with(vm) {
            if (roomId.isBlank() || timelineEvents.isEmpty()) return

            RoomTimelineCache.saveProcessedTimelineState(
                roomId = roomId,
                eventChainMap = eventChainMap.toMap(),
                editEventsMap = editEventsMap.toMap()
            )

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Saved processed timeline state for room $roomId (${eventChainMap.size} chains, ${editEventsMap.size} edits)",
                )
        }
    }

    fun restoreFromLruCache(roomId: String): Boolean {
        with(vm) {

            // Get cached events from RoomTimelineCache
            val cachedEvents = RoomTimelineCache.getCachedEvents(roomId) ?: return false

            val processedState = RoomTimelineCache.getProcessedTimelineState(roomId)

            // Restore events
            timelineEvents = cachedEvents

            // Restore processed state if available
            if (processedState != null) {
                eventChainMap.clear()
                eventChainMap.putAll(processedState.eventChainMap)
                editEventsMap.clear()
                editEventsMap.putAll(processedState.editEventsMap)

                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Restored room $roomId from cache (${timelineEvents.size} events, ${processedState.eventChainMap.size} chains, ${processedState.editEventsMap.size} edits)",
                    )
            } else {
                // No processed state - will be rebuilt from events
                eventChainMap.clear()
                editEventsMap.clear()
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Restored room $roomId events from cache (${timelineEvents.size} events), but no processed state - will rebuild",
                    )
            }

            return true
        }
    }

    fun appendEventsToCachedRoom(roomId: String, newEvents: List<TimelineEvent>): Boolean {
        with(vm) {
            if (newEvents.isEmpty()) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: appendEventsToCachedRoom called for $roomId with 0 events",
                    )
                return true
            }

            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: appendEventsToCachedRoom called for $roomId with ${newEvents.size} events (eventIds: ${newEvents.take(3).map { it.eventId }.joinToString(", ")})",
                )
            }

            // Update RoomTimelineCache with new events from SyncIngestor
            // CRITICAL: This happens for ALL cached rooms, not just the current room
            // This ensures buffered sync_complete messages update caches for all cached rooms
            // preventing timeline gaps when those rooms are opened later
            val addedCount = RoomTimelineCache.mergePaginatedEvents(roomId, newEvents)
            if (BuildConfig.DEBUG) {
                val isCurrentRoom = currentRoomId == roomId
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: mergePaginatedEvents added $addedCount events to cache for $roomId (currentRoom: $isCurrentRoom)",
                )
            }
            RoomTimelineCache.markRoomAccessed(roomId)

            // Check if any event requires full re-render (edits, redactions, reactions)
            val requiresFullRerender =
                newEvents.any { event ->
                    val relationType =
                        event.relationType
                            ?: event.content?.optJSONObject("m.relates_to")?.optString("rel_type")
                    relationType == "m.replace" || // Edit
                        relationType == "m.annotation" || // Reaction
                        event.type == "m.room.redaction"
                }

            if (requiresFullRerender) {
                // Invalidate processed state for this room - will be rebuilt on next open
                RoomTimelineCache.clearProcessedTimelineState(roomId)
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Invalidated processed timeline state for $roomId (edit/redaction/reaction detected)",
                    )
                return false
            }

            // Check if room has cached events (raw events are in RoomTimelineCache)
            val cachedEvents = RoomTimelineCache.getCachedEvents(roomId) ?: return false

            // Append new events and re-sort
            // IMPORTANT: existingEventIds is derived from getCachedEvents(), which excludes
            // m.room.member events with timelineRowid<=0 (they are dropped by addEventsToCache).
            // Re-filter using the same guard so those events don't slip into eventChainMap below.
            val existingEventIds = cachedEvents.map { it.eventId }.toSet()
            val trulyNewEvents = newEvents.filter { it.eventId !in existingEventIds }
                .filter { event ->
                    if (event.type != "m.room.member" || event.timelineRowid != 0L) return@filter true
                    // Keep kicks (sender != stateKey, membership=leave) even with timelineRowid<=0
                    event.stateKey != null &&
                        event.sender != event.stateKey &&
                        event.content?.optString("membership") == "leave"
                }

            if (trulyNewEvents.isEmpty()) return true

            // CRITICAL FIX: Also add events to eventChainMap if this is the currently open room
            // This ensures events aren't lost when handlePaginationMerge rebuilds from
            // eventChainMap
            if (currentRoomId == roomId) {
                for (event in trulyNewEvents) {
                    val isEdit = isEditEvent(event)
                    if (isEdit) {
                        editEventsMap[event.eventId] = event
                    } else {
                        val existingEntry = eventChainMap[event.eventId]
                        if (existingEntry == null) {
                            eventChainMap[event.eventId] =
                                AppViewModel.EventChainEntry(
                                    eventId = event.eventId,
                                    ourBubble = event,
                                    replacedBy = null,
                                    originalTimestamp = event.timestamp,
                                )
                        } else if (event.timestamp > existingEntry.originalTimestamp) {
                            // Update with newer version
                            eventChainMap[event.eventId] =
                                existingEntry.copy(
                                    ourBubble = event,
                                    originalTimestamp = event.timestamp,
                                )
                        }
                    }
                }
                // Process edit relationships for newly added events
                processEditRelationships()

                // Update processed state in cache
                RoomTimelineCache.saveProcessedTimelineState(
                    roomId = roomId,
                    eventChainMap = eventChainMap.toMap(),
                    editEventsMap = editEventsMap.toMap(),
                )

                // CRITICAL FIX: Defer timeline rebuild during batch processing to avoid rebuilding
                // the entire timeline for each sync_complete. Instead, rebuild once after batch
                // completes.
                // This prevents UI flicker and improves performance when processing many batched
                // sync_completes.
                val isBatchProcessing = isProcessingSyncBatch.value
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: appendEventsToCachedRoom - isBatchProcessing=$isBatchProcessing for room $roomId (${trulyNewEvents.size} new events)",
                    )
                }
                if (isBatchProcessing) {
                    // Batch is processing - defer rebuild until batch completes
                    roomsNeedingRebuildDuringBatch.add(roomId)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Batch processing in progress - deferring timeline rebuild for $roomId (will rebuild after batch completes)",
                        )
                    }
                } else {
                    // Not batch processing - rebuild immediately
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Not batch processing - rebuilding timeline immediately for room $roomId",
                        )
                    }
                    buildTimelineFromChain()
                }
            }

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Appended ${trulyNewEvents.size} events to cached room $roomId",
                )

            // Check for missing m.in_reply_to targets after adding events from sync_complete
            // If a message references a reply target that's not in the cache, fetch it via
            // get_event
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    // Re-check cache after adding new events to ensure we have the latest state
                    val cachedEvents = RoomTimelineCache.getCachedEvents(roomId) ?: emptyList()
                    val cachedEventIds = cachedEvents.map { it.eventId }.toSet()
                    val missingReplyTargets =
                        mutableSetOf<Pair<String, String>>() // (roomId, eventId)

                    // Check all newly added events for m.in_reply_to references
                    for (event in newEvents) {
                        // Check for m.in_reply_to in both content and decrypted
                        val replyToEventId =
                            event.content
                                ?.optJSONObject("m.relates_to")
                                ?.optJSONObject("m.in_reply_to")
                                ?.optString("event_id")
                                ?: event.decrypted
                                    ?.optJSONObject("m.relates_to")
                                    ?.optJSONObject("m.in_reply_to")
                                    ?.optString("event_id")

                        if (replyToEventId != null && replyToEventId.isNotBlank()) {
                            // Check if the reply target is in the cache
                            if (!cachedEventIds.contains(replyToEventId)) {
                                missingReplyTargets.add(Pair(event.roomId, replyToEventId))
                                if (BuildConfig.DEBUG)
                                    android.util.Log.d(
                                        "Andromuks",
                                        "AppViewModel: Missing reply target event_id=$replyToEventId for event ${event.eventId} in room ${event.roomId} (from sync_complete)",
                                    )
                            }
                        }
                    }

                    // Fetch missing reply targets via get_event
                    if (missingReplyTargets.isNotEmpty()) {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Fetching ${missingReplyTargets.size} missing reply target events via get_event (from sync_complete)",
                            )

                        for ((targetRoomId, targetEventId) in missingReplyTargets) {
                            // Use a suspend function to fetch the event
                            val deferred = CompletableDeferred<TimelineEvent?>()
                            withContext(Dispatchers.Main) {
                                getEvent(targetRoomId, targetEventId) { event ->
                                    deferred.complete(event)
                                }
                            }

                            val fetchedEvent = withTimeoutOrNull(5000L) { deferred.await() }

                            if (fetchedEvent != null) {
                                // Add the fetched event to the cache
                                val memberMap = RoomMemberCache.getRoomMembers(targetRoomId)
                                val eventsJsonArray = org.json.JSONArray()
                                eventsJsonArray.put(fetchedEvent.toRawJsonObject())
                                RoomTimelineCache.addEventsFromSync(
                                    targetRoomId,
                                    eventsJsonArray,
                                    memberMap,
                                )
                                if (BuildConfig.DEBUG)
                                    android.util.Log.d(
                                        "Andromuks",
                                        "AppViewModel: Fetched and cached missing reply target event_id=$targetEventId for room $targetRoomId (from sync_complete)",
                                    )
                            } else {
                                android.util.Log.w(
                                    "Andromuks",
                                    "AppViewModel: Failed to fetch missing reply target event_id=$targetEventId for room $targetRoomId (timeout or error, from sync_complete)",
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "Andromuks",
                        "AppViewModel: Error checking for missing reply targets from sync_complete",
                        e,
                    )
                }
            }

            return true
        }
    }

    fun hasInitialPaginate(roomId: String): Boolean =
        with(vm) { roomsPaginatedOnce.contains(roomId) }

    fun markInitialPaginate(roomId: String, reason: String) {
        with(vm) {
            val added = roomsPaginatedOnce.add(roomId)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Recorded initial paginate for $roomId (reason=$reason, added=$added)",
                )
            setAutoPaginationEnabled(false, "paginate_lock_$roomId")
        }
    }

    internal fun logSkippedPaginate(roomId: String, reason: String) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Skipping paginate for $roomId ($reason) - already paginated once this session",
                )
        }
    }

    fun processCachedEvents(
        roomId: String,
        cachedEvents: List<TimelineEvent>,
        openingFromNotification: Boolean,
        skipNetworkRequests: Boolean = false,
    ) {
        with(vm) {
            val ownMessagesInCache =
                cachedEvents.count {
                    it.sender == currentUserId &&
                        (it.type == "m.room.message" || it.type == "m.room.encrypted")
                }
            val cacheType =
                if (openingFromNotification && cachedEvents.size < 100) "notification-optimized"
                else "standard"
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ✓ CACHE HIT ($cacheType) - Instant room opening: ${cachedEvents.size} events (including $ownMessagesInCache of your own messages)",
                )
            if (ownMessagesInCache > 0) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: ★ Cache contains $ownMessagesInCache messages from YOU",
                    )
            }
            if (BuildConfig.DEBUG) {
                val firstCached = cachedEvents.firstOrNull()
                val lastCached = cachedEvents.lastOrNull()
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Cached snapshot for $roomId -> first=${firstCached?.eventId}@${firstCached?.timestamp}, " +
                            "last=${lastCached?.eventId}@${lastCached?.timestamp}",
                    )
            }

            // Clear and rebuild internal structures (but don't clear timelineEvents yet)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Clearing eventChainMap (had ${eventChainMap.size} entries) before processing ${cachedEvents.size} cached events",
                )
            eventChainMap.clear()
            editEventsMap.clear()
            MessageVersionsCache.clear()
            // editToOriginal is computed from messageVersions, no need to clear separately
            MessageReactionsCache.clear()
            messageReactions = emptyMap()
            roomsWithLoadedReceipts.remove(roomId)
            roomsWithLoadedReactions.remove(roomId)

            // Reset pagination state
            smallestRowId = -1L
            isPaginating = false
            hasMoreMessages = true

            // Ensure member cache exists for this room (singleton cache handles this automatically)

            // Populate edit chain mapping from cached events
            // Process synchronously to ensure all events are added before building timeline
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Processing ${cachedEvents.size} cached events into eventChainMap",
                )
            var regularEventCount = 0
            var editEventCount = 0

            for (event in cachedEvents) {
                val isEditEvent = isEditEvent(event)

                if (isEditEvent) {
                    editEventsMap[event.eventId] = event
                    editEventCount++
                } else if (event.type == "com.beeper.message_send_status") {
                    // Bridge delivery confirmation from cached history
                    val content = event.content
                    if (content != null) {
                        val relatesTo = content.optJSONObject("m.relates_to")
                        val relType = relatesTo?.optString("rel_type")
                        val relatedEventId = relatesTo?.optString("event_id")?.takeIf { it.isNotBlank() }
                        val status = content.optString("status")
                        if (relType == "m.reference" && relatedEventId != null && !status.isNullOrBlank()) {
                            val deliveredToUsers = if (content.has("delivered_to_users")) {
                                content.optJSONArray("delivered_to_users")
                                    ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } }
                            } else null
                            vm.processBridgeSendStatus(roomId, relatedEventId, event.sender, status, deliveredToUsers, event.timestamp)
                            // Track status eventId → original message eventId for receipt remapping.
                            if (event.eventId.isNotBlank()) {
                                vm.bridgeStatusEventToMessageId[event.eventId] = relatedEventId
                            }
                        }
                    }
                } else {
                    eventChainMap[event.eventId] =
                        AppViewModel.EventChainEntry(
                            eventId = event.eventId,
                            ourBubble = event,
                            replacedBy = null,
                            originalTimestamp = event.timestamp,
                        )
                    regularEventCount++
                }
            }

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Added ${regularEventCount} regular events and ${editEventCount} edit events to maps",
                )
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: eventChainMap now has ${eventChainMap.size} entries (expected ${cachedEvents.size - editEventCount} regular events)",
                )

            // SAFETY: Remove any edit events that might have been incorrectly added to
            // eventChainMap
            // (This shouldn't happen with the fix, but clean up any existing bad state)
            val editEventIds = editEventsMap.keys.toSet()
            val editEventsInChain = eventChainMap.keys.filter { editEventIds.contains(it) }
            if (editEventsInChain.isNotEmpty()) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: Removing ${editEventsInChain.size} edit events incorrectly added to eventChainMap",
                )
                editEventsInChain.forEach { eventChainMap.remove(it) }
            }

            // DIAGNOSTIC: Verify all events were added
            if (eventChainMap.size != regularEventCount) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: ⚠️ MISMATCH - eventChainMap has ${eventChainMap.size} entries but we added $regularEventCount regular events!",
                )
            }

            // DELIVERED UPGRADE: bridge status events were just processed above, populating
            // messageBridgeSendStatus and bridgeStatusEventToMessageId. At VM init time,
            // populateReadReceiptsFromCache ran while bridgeStatusEventToMessageId was still empty,
            // so the "delivered" upgrade in ReadReceiptsTypingCoordinator was skipped. Now that
            // we have the mapping, upgrade any receipts already in readReceipts for this room.
            if (vm.bridgeStatusEventToMessageId.isNotEmpty()) {
                synchronized(vm.readReceiptsLock) {
                    vm.readReceipts.forEach { (eventId, receipts) ->
                        if (receipts.any { it.roomId == roomId }) {
                            val targetMessageId = vm.bridgeStatusEventToMessageId[eventId] ?: eventId
                            if (vm.messageBridgeSendStatus.containsKey(targetMessageId)) {
                                vm.updateBridgeStatus(targetMessageId, "delivered")
                            }
                        }
                    }
                }
            }

            // Process cached events to establish edit relationships
            // All events (including edit events) are already in the cache - no need to load from DB
            // Edit events from both paginate and sync_complete are in the cache
            val editEventsInCache = cachedEvents.filter { isEditEvent(it) }

            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Processing cached events - ${cachedEvents.size} total, ${editEventsInCache.size} edit events in cache",
                )

                // Debug: Log edit events in cache to verify they're being identified
                if (editEventsInCache.isNotEmpty()) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Edit events in cache: ${editEventsInCache.map { "${it.eventId} -> ${it.content?.optJSONObject("m.relates_to")?.optString("event_id") ?: it.decrypted?.optJSONObject("m.relates_to")?.optString("event_id")}" }.joinToString(", ")}",
                    )
                }
            }

            // Run heavy chain processing on background thread; state update happens on Main in
            // executeTimelineRebuild
            viewModelScope.launch(Dispatchers.Default) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Processing ${cachedEvents.size} cached events to establish version relationships (on Default)",
                    )
                }
                processVersionedMessages(cachedEvents)
                if (BuildConfig.DEBUG) {
                    val eventsWithEdits =
                        cachedEvents.filter {
                            !isEditEvent(it) && messageVersions[it.eventId]?.versions?.size ?: 0 > 1
                        }
                    if (eventsWithEdits.isNotEmpty()) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: ✅ Established edit relationships for ${eventsWithEdits.size} messages after processing cached events",
                        )
                    } else if (editEventsInCache.isNotEmpty()) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: ⚠️ Found ${editEventsInCache.size} edit events in cache but no version relationships established - this indicates a problem",
                        )
                    }
                }
                processEditRelationships()
                val rebuildComplete = CompletableDeferred<Unit>()
                buildTimelineFromChain(rebuildComplete)
                rebuildComplete.await()
                withContext(Dispatchers.Main) {
                    val expectedMinEvents = cachedEvents.size - editEventCount
                    if (timelineEvents.size < expectedMinEvents * 0.9) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: ⚠️ SIGNIFICANT EVENT LOSS - Timeline has ${timelineEvents.size} events " +
                                "but expected at least ${expectedMinEvents} (from ${cachedEvents.size} cached events, $editEventCount edits filtered)",
                        )
                    }
                    val latestTimelineEvent = timelineEvents.lastOrNull()
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Timeline latest event=${latestTimelineEvent?.eventId} timelineRowId=${latestTimelineEvent?.timelineRowid} ts=${latestTimelineEvent?.timestamp}",
                        )
                    loadReactionsForRoom(roomId, cachedEvents)
                    applyAggregatedReactionsFromEvents(cachedEvents, "cache")
                    updateRoomStateFromTimelineEvents(roomId, cachedEvents)
                    updateMemberProfilesFromTimelineEvents(roomId, cachedEvents)
                    val smallestCached =
                        cachedEvents.minByOrNull { it.timelineRowid }?.timelineRowid ?: -1L
                    if (smallestCached > 0) smallestRowId = smallestCached
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: ✅ Room opened with ${timelineEvents.size} cached events (chain processing on background)",
                        )
                    if (openingFromNotification) isPendingNavigationFromNotification = false
                    val currentNavigationState = getRoomNavigationState(roomId)
                    if (
                        !skipNetworkRequests &&
                            currentNavigationState?.essentialDataLoaded != true &&
                            !pendingRoomStateRequests.contains(roomId)
                    ) {
                        requestRoomState(roomId)
                    }
                    if (currentNavigationState?.memberDataLoaded != true) {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: SKIPPING member data loading (using opportunistic loading)",
                            )
                        navigationCache[roomId] =
                            currentNavigationState?.copy(memberDataLoaded = true)
                                ?: AppViewModel.RoomNavigationState(roomId, memberDataLoaded = true)
                    }
                    val shouldMarkAsRead =
                        if (BubbleTracker.isBubbleOpen(roomId)) {
                            BubbleTracker.isBubbleVisible(roomId)
                        } else {
                            true
                        }
                    if (shouldMarkAsRead) {
                        // Prefer RoomListCache.getLatestEventId — it tracks the absolute latest
                        // event seen for this room across all sync_complete cycles, even if the room
                        // was not actively cached when that event arrived (so the cache may lag).
                        val cacheMaxId = cachedEvents.maxByOrNull { it.timestamp }?.eventId
                        val latestKnownId = RoomListCache.getLatestEventId(roomId) ?: cacheMaxId
                        if (latestKnownId != null) markRoomAsRead(roomId, latestKnownId)
                    } else {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Skipping mark as read for room $roomId (bubble is minimized)",
                            )
                    }
                    if (!skipNetworkRequests && REACTION_BACKFILL_ON_OPEN_ENABLED) {
                        requestHistoricalReactions(roomId, smallestCached)
                    }
                }
            }
        }
    }

    fun requestRoomTimeline(roomId: String, useLruCache: Boolean = true) {
        with(vm) {
            android.util.Log.d(
                "Andromuks",
                "🟢 requestRoomTimeline: START - roomId=$roomId, useLruCache=$useLruCache, currentRoomId=$currentRoomId, isTimelineLoading=$isTimelineLoading, isPaginating=$isPaginating",
            )

            // Check if we're refreshing the same room before updating currentRoomId
            val isRefreshingSameRoom = currentRoomId == roomId && timelineEvents.isNotEmpty()

            // LRU CACHE: Try to restore from cache first (instant room switch)
            if (useLruCache && !isRefreshingSameRoom && restoreFromLruCache(roomId)) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: ✅ INSTANT room switch from LRU cache for $roomId (${timelineEvents.size} events)",
                    )
                updateCurrentRoomIdInPrefs(roomId)
                isTimelineLoading = false
                // Mark as actively cached so sync_complete updates keep this cache fresh.
                RoomTimelineCache.markRoomAsCached(roomId)
                RoomTimelineCache.markRoomAccessed(roomId)

                // Store room open timestamp for animation purposes
                val openTimestamp = System.currentTimeMillis()
                roomOpenTimestamps[roomId] = openTimestamp

                // Still request room state in background for any updates
                if (isWebSocketConnected() && !pendingRoomStateRequests.contains(roomId)) {
                    val stateRequestId = requestIdCounter++
                    synchronized(roomStateRequests) { roomStateRequests[stateRequestId] = roomId }
                    pendingRoomStateRequests.add(roomId)
                    sendWebSocketCommand(
                        "get_room_state",
                        stateRequestId,
                        mapOf(
                            "room_id" to roomId,
                            "include_members" to false,
                            "fetch_members" to false,
                            "refetch" to false,
                        ),
                    )
                }

                // Mark room as read up to the latest known event. RoomListCache tracks the
                // absolute latest event even for rooms that weren't actively cached during that
                // sync, so prefer it over the cache max.
                val shouldMarkAsRead = if (BubbleTracker.isBubbleOpen(roomId)) {
                    BubbleTracker.isBubbleVisible(roomId)
                } else {
                    true
                }
                if (shouldMarkAsRead) {
                    val cacheMaxId = RoomTimelineCache.getCachedEvents(roomId)
                        ?.maxByOrNull { it.timestamp }?.eventId
                    val latestKnownId = RoomListCache.getLatestEventId(roomId) ?: cacheMaxId
                    if (latestKnownId != null) markRoomAsRead(roomId, latestKnownId)
                }

                // Background paginate to pull any events the LRU restore may have missed.
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
                }

                return
            }

            // CRITICAL: Store room open timestamp when opening a room (not when refreshing the same
            // room)
            // This timestamp will be used to determine which messages should animate
            // Only messages with timestamp NEWER than this will animate
            if (!isRefreshingSameRoom || !roomOpenTimestamps.containsKey(roomId)) {
                val openTimestamp = System.currentTimeMillis()
                roomOpenTimestamps[roomId] = openTimestamp
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Stored room open timestamp for $roomId: $openTimestamp (only messages newer than this will animate)",
                    )
            }

            updateCurrentRoomIdInPrefs(roomId)

            // OPPORTUNISTIC PROFILE LOADING: Only request room state without members to prevent OOM
            // Member profiles will be loaded on-demand when actually needed for rendering
            if (isWebSocketConnected() && !pendingRoomStateRequests.contains(roomId)) {
                val stateRequestId = requestIdCounter++
                roomStateRequests[stateRequestId] = roomId
                pendingRoomStateRequests.add(roomId)
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Requesting room state WITHOUT members to prevent OOM (reqId: $stateRequestId)",
                    )
                sendWebSocketCommand(
                    "get_room_state",
                    stateRequestId,
                    mapOf(
                        "room_id" to roomId,
                        "include_members" to false, // CRITICAL: Don't load all members
                        "fetch_members" to false,
                        "refetch" to false,
                    ),
                )
            }

            // NAVIGATION PERFORMANCE: Check cached navigation state and use partial loading
            val navigationState = getRoomNavigationState(roomId)
            if (navigationState != null && navigationState.essentialDataLoaded) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: NAVIGATION OPTIMIZATION - Using cached navigation state for: $roomId",
                    )

                // Load additional details in background if needed
                loadRoomDetails(roomId, navigationState)
            }

            // Check if we're opening from a notification (for optimized cache handling)
            val openingFromNotification =
                isPendingNavigationFromNotification && pendingRoomNavigation == roomId

            // PROACTIVE CACHE MANAGEMENT: Check if room is actively cached
            val cachedEvents = RoomTimelineCache.getCachedEvents(roomId)
            val isActivelyCached = RoomTimelineCache.isRoomActivelyCached(roomId)

            // CRITICAL FIX: Removed isActivelyCached requirement from instant cache hit
            // If we have events in RAM, we should ALWAYS show them immediately.
            // If not actively cached, we will activate it and paginate in the background.
            if (cachedEvents != null && cachedEvents.isNotEmpty()) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: ✅ Found ${cachedEvents.size} events in RoomTimelineCache for $roomId (activelyCached=$isActivelyCached)",
                    )

                // CRITICAL FIX: Mark as actively cached so sync_complete updates start being
                // applied
                // immediately
                // This is essential if we're opening a room that was pre-cached (e.g. via
                // preemptive
                // pagination)
                // or if the WebSocket reconnected after being idle (clearing activelyCachedRooms
                // but
                // keeping events).
                if (!isActivelyCached) {
                    RoomTimelineCache.markRoomAsCached(roomId)
                }

                // BACKFILL: Seed renderable cache from cache snapshot so UI can avoid recomputing
                // relations
                // on open.
                persistRenderableEvents(roomId, cachedEvents)

                // Process events through chain processing (builds timeline structure)
                // Include redaction events so "Removed by X for Y" renders correctly
                processCachedEvents(
                    roomId,
                    RoomTimelineCache.getCachedEventsForTimeline(roomId),
                    openingFromNotification = false,
                )

                // Mark room as accessed in RoomTimelineCache for LRU eviction
                RoomTimelineCache.markRoomAccessed(roomId)

                // Still send paginate request to fetch any newer events from server
                // Only send when opening a new room (not refreshing the same room)
                // GUARD: Check if a paginate request is already pending for this room (atomic
                // check-and-set)
                val wasAdded = roomsWithPendingPaginate.add(roomId)

                if (
                    !isRefreshingSameRoom &&
                        isWebSocketConnected() &&
                        AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT > 0 &&
                        wasAdded
                ) {
                    val paginateRequestId = requestIdCounter++
                    timelineRequests[paginateRequestId] = roomId
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Sending paginate request to fetch newer events for $roomId (limit=$AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT, reqId=$paginateRequestId)",
                        )
                    val result =
                        sendWebSocketCommand(
                            "paginate",
                            paginateRequestId,
                            mapOf(
                                "room_id" to roomId,
                                "max_timeline_id" to 0, // Fetch latest events
                                "limit" to AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT,
                                "reset" to false,
                            ),
                        )

                    if (result != WebSocketResult.SUCCESS) {
                        if (!isWebSocketConnected()) {
                            // Truly not connected — drop tracking; will retry on next room open.
                            timelineRequests.remove(paginateRequestId)
                            roomsWithPendingPaginate.remove(roomId)
                            android.util.Log.w(
                                "Andromuks",
                                "AppViewModel: Failed to send paginate for newer events for $roomId (not connected): $result",
                            )
                        } else {
                            // Connected but canSendCommandsToBackend=false: command was queued in
                            // pendingCommandsQueue. Keep tracking so the response is handled when
                            // flushPendingQueue() re-sends it after init_complete completes.
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Paginate for newer events queued (canSendCommandsToBackend=false) for $roomId (reqId=$paginateRequestId) — keeping tracking",
                            )
                        }
                    }
                } else if (!wasAdded && BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Skipping paginate request for newer events for $roomId - already have a pending paginate request",
                    )
                } else if (!wasAdded) {
                    // Remove if we didn't add it (already pending)
                    roomsWithPendingPaginate.remove(roomId)
                }

                return
            }

            // CACHE EMPTY OR NOT ACTIVELY CACHED: Issue paginate command to fill the cache
            android.util.Log.d(
                "Andromuks",
                "🟢 requestRoomTimeline: Cache empty/missing - roomId=$roomId, cachedEvents=${cachedEvents?.size ?: 0}, isActivelyCached=$isActivelyCached, isWebSocketConnected=${isWebSocketConnected()}",
            )

            if (!isWebSocketConnected()) {
                android.util.Log.w(
                    "Andromuks",
                    "🟢 requestRoomTimeline: WebSocket not connected - roomId=$roomId, setting loading=true and clearing timeline",
                )
                // Set loading state and clear timeline
                timelineEvents = emptyList()
                isTimelineLoading = true
                android.util.Log.d(
                    "Andromuks",
                    "🟢 requestRoomTimeline: EXIT (WebSocket not connected) - roomId=$roomId, isTimelineLoading=$isTimelineLoading",
                )
                return
            }

            // CRITICAL FIX: When cache is insufficient (< half of paginate limit), always paginate
            // when
            // opening a room
            // This ensures rooms with evicted cache or minimal cache still get populated
            // AUTO_PAGINATION_ENABLED only controls automatic pagination for loading more history,
            // not
            // initial pagination
            val currentCachedCount = RoomTimelineCache.getCachedEventCount(roomId)
            val cacheInsufficientThreshold = AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT / 2
            val cacheInsufficient = currentCachedCount < cacheInsufficientThreshold

            if (cacheInsufficient && !isRefreshingSameRoom) {
                // Cache is insufficient - send paginate request to populate it
                // GUARD: Check if a paginate request is already pending for this room (atomic
                // check-and-set)
                val wasAdded = roomsWithPendingPaginate.add(roomId)
                if (!wasAdded) {
                    // Room already has a pending paginate request
                    android.util.Log.d(
                        "Andromuks",
                        "🟢 requestRoomTimeline: Paginate already pending - roomId=$roomId",
                    )
                    timelineEvents = emptyList()
                    isTimelineLoading = true
                    return
                }

                val paginateRequestId = requestIdCounter++
                timelineRequests[paginateRequestId] = roomId
                android.util.Log.d(
                    "Andromuks",
                    "🟢 requestRoomTimeline: Cache insufficient ($currentCachedCount < $cacheInsufficientThreshold) - sending paginate - roomId=$roomId, requestId=$paginateRequestId, limit=$AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT",
                )

                // Set loading state BEFORE sending command
                timelineEvents = emptyList()
                isTimelineLoading = true

                val result =
                    sendWebSocketCommand(
                        "paginate",
                        paginateRequestId,
                        mapOf(
                            "room_id" to roomId,
                            "max_timeline_id" to 0, // Fetch latest events
                            "limit" to AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT,
                            "reset" to false,
                        ),
                    )

                if (result == WebSocketResult.SUCCESS) {
                    // PROACTIVE CACHE MANAGEMENT: Mark room as actively cached so SyncIngestor
                    // knows to
                    // update it
                    RoomTimelineCache.markRoomAsCached(roomId)
                    markInitialPaginate(roomId, "cache_insufficient")
                    android.util.Log.d(
                        "Andromuks",
                        "🟢 requestRoomTimeline: Paginate sent successfully - roomId=$roomId, requestId=$paginateRequestId, marked as actively cached, waiting for response...",
                    )
                } else if (!isWebSocketConnected()) {
                    // Truly not connected — drop tracking and clear loading state.
                    android.util.Log.w(
                        "Andromuks",
                        "🟢 requestRoomTimeline: FAILED to send paginate (not connected) - roomId=$roomId, requestId=$paginateRequestId, result=$result, removing from tracking",
                    )
                    timelineRequests.remove(paginateRequestId)
                    roomsWithPendingPaginate.remove(roomId)
                    isTimelineLoading = false
                } else {
                    // Connected but canSendCommandsToBackend=false: command was queued in
                    // pendingCommandsQueue. Keep tracking so the response is handled when
                    // flushPendingQueue() re-sends it after init_complete completes.
                    // Keep isTimelineLoading=true so the user sees the loading indicator.
                    RoomTimelineCache.markRoomAsCached(roomId)
                    markInitialPaginate(roomId, "cache_insufficient_queued")
                    android.util.Log.d(
                        "Andromuks",
                        "🟢 requestRoomTimeline: Paginate queued (canSendCommandsToBackend=false) - roomId=$roomId, requestId=$paginateRequestId — keeping tracking",
                    )
                }

                // Reset pagination state for new room
                smallestRowId = -1L
                isPaginating = false
                hasMoreMessages = true

                // Clear edit chain mapping when opening a new room
                eventChainMap.clear()
                editEventsMap.clear()
                MessageVersionsCache.clear()
                MessageReactionsCache.clear()
                messageReactions = emptyMap()
                roomsWithLoadedReactions.remove(roomId)

                // Load essential data
                val missNavigationState = getRoomNavigationState(roomId)
                if (
                    missNavigationState?.essentialDataLoaded != true &&
                        !pendingRoomStateRequests.contains(roomId)
                ) {
                    requestRoomState(roomId)
                }

                return
            }

            // Cache is sufficient OR we're refreshing the same room - handle accordingly
            if (isRefreshingSameRoom) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Preserving existing timeline on resume (${timelineEvents.size} events)",
                    )
                isTimelineLoading = false
            } else {
                // CRITICAL FIX: If we reached here with events in cache, we MUST call
                // processCachedEvents
                // This happens if currentCachedCount was sufficient but cachedEvents was somehow
                // missing in
                // the first check
                // or other race conditions.
                val eventsToProcess = cachedEvents ?: RoomTimelineCache.getCachedEvents(roomId)
                if (eventsToProcess != null && eventsToProcess.isNotEmpty()) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Cache has $currentCachedCount events (>= 50) - processing them now",
                        )
                    // Include redaction events for correct "Removed by X for Y" rendering
                    processCachedEvents(
                        roomId,
                        RoomTimelineCache.getCachedEventsForTimeline(roomId),
                        false,
                    )
                } else {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Cache reported sufficient count ($currentCachedCount) but no events found - setting loading false anyway",
                        )
                    isTimelineLoading = false
                }
            }
        }
    }

    suspend fun flushSyncBatchForRoom(roomId: String) {
        with(vm) {

            // Ensure the room is marked as actively cached BEFORE flushing,
            // so events for this room are added to cache during ingestion.
            RoomTimelineCache.markRoomAsCached(roomId)
            RoomTimelineCache.addOpenedRoom(roomId)

            val flushJob = syncBatchProcessor.forceFlushBatch()
            flushJob?.join()

            if (BuildConfig.DEBUG) {
                val count = RoomTimelineCache.getCachedEventCount(roomId)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: flushSyncBatchForRoom($roomId) complete – cache now has $count events",
                )
            }
        }
    }

    fun addTimelineEvent(event: TimelineEvent) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: addTimelineEvent called for event: ${event.eventId}, type=${event.type}, roomId=${event.roomId}, currentRoomId=$currentRoomId",
                )

            // Only add to timeline if it's for the current room
            if (event.roomId == currentRoomId) {
                val currentEvents = timelineEvents.toMutableList()
                currentEvents.add(event)
                timelineEvents = currentEvents.sortedBy { it.timestamp }
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Added event to timeline, total events: ${timelineEvents.size}",
                    )
            } else {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Event roomId (${event.roomId}) doesn't match currentRoomId ($currentRoomId), not adding to timeline",
                    )
            }
        }
    }

    fun handleTimelineResponse(requestId: Int, data: Any) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "🟡 handleTimelineResponse: START - requestId=$requestId, dataType=${data::class.java.simpleName}, currentRoomId=$currentRoomId, isTimelineLoading=$isTimelineLoading",
                )

            // Determine request type and get room ID
            val roomId =
                timelineRequests[requestId]
                    ?: paginateRequests[requestId]
                    ?: backgroundPrefetchRequests[requestId]
            if (roomId == null) {
                android.util.Log.w(
                    "Andromuks",
                    "🟡 handleTimelineResponse: UNKNOWN requestId - requestId=$requestId, timelineRequests=${timelineRequests.keys}, paginateRequests=${paginateRequests.keys}, backgroundPrefetchRequests=${backgroundPrefetchRequests.keys}",
                )
                return
            }

            val isPaginateRequest = paginateRequests.containsKey(requestId)
            val isBackgroundPrefetchRequest = backgroundPrefetchRequests.containsKey(requestId)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "🟡 handleTimelineResponse: Processing - roomId=$roomId, requestId=$requestId, isPaginate=$isPaginateRequest, isBackgroundPrefetch=$isBackgroundPrefetchRequest, currentRoomId=$currentRoomId, isTimelineLoading=$isTimelineLoading",
                )

            // CRITICAL FIX: Parse has_more field BEFORE processing events, so we have it even if
            // events
            // array is empty
            var hasMoreFromResponse: Boolean? = null
            if (data is JSONObject && isPaginateRequest) {
                hasMoreFromResponse =
                    data.optBoolean("has_more", true) // Default to true if not present
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Parsed has_more=$hasMoreFromResponse from response BEFORE processing events",
                    )
            }

            var totalReactionsProcessed = 0

            // Process events array - main event processing logic
            fun processEventsArray(eventsArray: JSONArray): Int {
                val eventCount = eventsArray.length()
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "🟡 processEventsArray: START - roomId=$roomId, requestId=$requestId, eventCount=$eventCount, isPaginate=$isPaginateRequest, isBackgroundPrefetch=$isBackgroundPrefetchRequest, currentRoomId=$currentRoomId, isTimelineLoading=$isTimelineLoading",
                    )
                if (eventCount == 0) {
                    android.util.Log.w(
                        "Andromuks",
                        "🟡 processEventsArray: EMPTY response - roomId=$roomId, requestId=$requestId, isPaginate=$isPaginateRequest",
                    )
                }
                val timelineList = mutableListOf<TimelineEvent>()
                val allEvents = mutableListOf<TimelineEvent>() // For version processing
                val memberMap = RoomMemberCache.getRoomMembers(roomId)

                var ownMessageCount = 0
                var reactionProcessedCount = 0
                var filteredByRowId = 0
                var filteredByType = 0
                for (i in 0 until eventsArray.length()) {
                    val eventJson = eventsArray.optJSONObject(i)
                    if (eventJson != null) {
                        val event = TimelineEvent.fromJson(eventJson)
                        allEvents.add(event) // Collect all events for version processing

                        // Track our own messages
                        if (
                            event.sender == currentUserId &&
                                (event.type == "m.room.message" || event.type == "m.room.encrypted")
                        ) {
                            ownMessageCount++
                            val bodyPreview =
                                when {
                                    event.type == "m.room.message" ->
                                        event.content?.optString("body", "")?.take(50)
                                    event.type == "m.room.encrypted" ->
                                        event.decrypted?.optString("body", "")?.take(50)
                                    else -> ""
                                }
                            // android.util.Log.d("Andromuks", "AppViewModel: [PAGINATE] ★ Found OUR
                            // message in
                            // paginate response: ${event.eventId} body='$bodyPreview'
                            // timelineRowid=${event.timelineRowid}")
                        }

                        // Process member events using helper function
                        if (event.type == "m.room.member" && event.timelineRowid == -1L) {
                            val mutableMemberMap = memberMap.toMutableMap()
                            processMemberEvent(event, mutableMemberMap)
                            // Update singleton cache with changes
                            mutableMemberMap.forEach { (userId, profile) ->
                                RoomMemberCache.updateMember(roomId, userId, profile)
                            }
                        } else {
                            // Process reaction events using helper function
                            if (event.type == "m.reaction") {
                                if (reactionCoordinator.processReactionFromTimeline(event)) {
                                    reactionProcessedCount++
                                }
                                filteredByType++
                            } else if (event.type == "com.beeper.message_send_status") {
                                // Bridge delivery confirmation from paginated history
                                val content = event.content
                                if (content != null) {
                                    val relatesTo = content.optJSONObject("m.relates_to")
                                    val relType = relatesTo?.optString("rel_type")
                                    val relatedEventId = relatesTo?.optString("event_id")?.takeIf { it.isNotBlank() }
                                    val status = content.optString("status")
                                    if (relType == "m.reference" && relatedEventId != null && status.isNotBlank()) {
                                        val deliveredToUsers = if (content.has("delivered_to_users")) {
                                            content.optJSONArray("delivered_to_users")
                                                ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } }
                                        } else null
                                        vm.processBridgeSendStatus(roomId, relatedEventId, event.sender, status, deliveredToUsers, event.timestamp)
                                        // Track status eventId → original message eventId for receipt remapping.
                                        if (event.eventId.isNotBlank()) {
                                            vm.bridgeStatusEventToMessageId[event.eventId] = relatedEventId
                                        }
                                    }
                                }
                                filteredByType++
                            } else {
                                // Define allowed event types that should appear in timeline
                                // These match the allowedEventTypes in RoomTimelineScreen and
                                // BubbleTimelineScreen
                                val allowedEventTypes =
                                    setOf(
                                        "m.room.message",
                                        "m.room.encrypted",
                                        "m.room.member",
                                        "m.room.name",
                                        "m.room.topic",
                                        "m.room.avatar",
                                        "m.room.pinned_events",
                                        "m.room.tombstone",
                                        "m.sticker",
                                        "m.room.redaction", // Needed so redaction events reach cache (addEventsToCache stores them in cache.redactionEvents for sender lookup)
                                    )

                                // Check if this is a kick (leave event where sender != state_key)
                                // Kicks should appear in timeline even with negative timelineRowid
                                // Note: Member events with timelineRowid == -1 are processed
                                // separately above (line
                                // 12562)
                                val isKick =
                                    event.type == "m.room.member" &&
                                        event.timelineRowid < 0 &&
                                        event.stateKey != null &&
                                        event.sender != event.stateKey &&
                                        event.content?.optString("membership") == "leave"

                                // Filtering logic:
                                // 1. Allow all allowed event types regardless of timelineRowid
                                //    (timelineRowid can be negative for many valid timeline events,
                                // including
                                // messages)
                                // 2. For member events with negative timelineRowid, only allow
                                // kicks
                                //    (member events with timelineRowid >= 0 are always allowed)
                                // 3. Messages, encrypted messages, stickers, and system events are
                                // always allowed
                                //    regardless of timelineRowid (they can have timelineRowid == -1
                                // in some cases)
                                val shouldAdd =
                                    when {
                                        allowedEventTypes.contains(event.type) -> {
                                            // For member events with negative timelineRowid, only
                                            // allow kicks
                                            // All other allowed event types (messages, system
                                            // events, etc.) are allowed
                                            // even with negative timelineRowid
                                            if (
                                                event.type == "m.room.member" &&
                                                    event.timelineRowid < 0
                                            ) {
                                                isKick
                                            } else {
                                                true
                                            }
                                        }
                                        else -> false
                                    }

                                if (shouldAdd) {
                                    timelineList.add(event)
                                } else {
                                    filteredByRowId++
                                    if (BuildConfig.DEBUG && filteredByRowId <= 5) {
                                        android.util.Log.d(
                                            "Andromuks",
                                            "AppViewModel: Filtered event ${event.eventId} type=${event.type} timelineRowid=${event.timelineRowid}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Processed events - timeline=${timelineList.size}, members=${memberMap.size}, ownMessages=$ownMessageCount, reactions=$reactionProcessedCount, filteredByRowId=$filteredByRowId, filteredByType=$filteredByType",
                    )
                if (ownMessageCount > 0) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: ★★★ PAGINATE RESPONSE CONTAINS $ownMessageCount OF YOUR OWN MESSAGES ★★★",
                        )
                }
                if (reactionProcessedCount > 0) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: ★★★ PROCESSED $reactionProcessedCount REACTIONS FROM PAGINATE RESPONSE ★★★",
                        )
                }

                // Track the latest event seen for this room so mark_read always has a valid target.
                allEvents.maxByOrNull { it.timestamp }?.let { latest ->
                    RoomListCache.updateLatestEvent(roomId, latest.eventId, latest.timestamp)
                }

                // OPTIMIZED: Process versioned messages (edits, redactions) - O(n)
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Processing ${allEvents.size} events for version tracking",
                    )
                processVersionedMessages(allEvents)

                // Handle empty pagination responses
                // Only mark as "no more messages" if backend actually returned 0 events OR fewer
                // events
                // than requested
                // Don't mark as empty if events were filtered out (e.g., all reactions/state
                // events)
                val totalEventsReturned = eventsArray.length()
                // CRITICAL FIX: When timelineList is empty, use has_more field to determine if more
                // messages available
                // Don't set hasMoreMessages = false unless backend explicitly says has_more = false
                if (timelineList.isEmpty()) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: ========================================",
                    )
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: ⚠️ EMPTY PAGINATION RESPONSE (requestId: $requestId)",
                    )
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Backend returned $totalEventsReturned events, timeline events: ${timelineList.size} (filtered: rowId=$filteredByRowId, type=$filteredByType)",
                    )

                    // Use has_more field if available, otherwise keep current state
                    if (hasMoreFromResponse != null) {
                        hasMoreMessages = hasMoreFromResponse
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Setting hasMoreMessages=${hasMoreFromResponse} based on has_more field from response",
                        )
                        if (!hasMoreFromResponse) {
                            android.util.Log.w(
                                "Andromuks",
                                "AppViewModel: 🏁 REACHED END OF MESSAGE HISTORY (has_more=false, empty response)",
                            )
                            appContext?.let { context ->
                                android.widget.Toast.makeText(
                                        context,
                                        "No more messages available",
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                        }
                    } else {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: No has_more field in response, keeping current hasMoreMessages state",
                        )
                    }

                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: ========================================",
                    )

                    // Clean up request tracking
                    paginateRequests.remove(requestId)
                    paginateRequestMaxTimelineIds.remove(requestId)
                    backgroundPrefetchRequests.remove(requestId)
                    isPaginating = false
                    android.util.Log.w(
                        "Andromuks",
                        "🟡 processEventsArray: EMPTY response handled - roomId=$roomId, requestId=$requestId, returning early, isTimelineLoading=$isTimelineLoading",
                    )
                    return reactionProcessedCount
                }

                if (timelineList.isNotEmpty()) {
                    // Handle background prefetch requests first - before any UI processing
                    if (backgroundPrefetchRequests.containsKey(requestId)) {
                        return handleBackgroundPrefetch(roomId, timelineList)
                    }

                    // Track oldest POSITIVE timelineRowId for initial paginate (when opening room)
                    // This will be used for the first pull-to-refresh
                    // CRITICAL: Only use positive timelineRowid values for pagination (negative
                    // values are
                    // for state events)
                    if (!paginateRequests.containsKey(requestId)) {
                        // Filter to only positive timelineRowids (pagination events only, exclude
                        // state events)
                        val positiveEvents = timelineList.filter { it.timelineRowid != 0L }
                        val oldestInResponse = positiveEvents.minOfOrNull { it.timelineRowid }

                        if (oldestInResponse != null) {
                            // timelineRowId of 0 is a bug (should never happen)
                            if (oldestInResponse == 0L) {
                                android.util.Log.e(
                                    "Andromuks",
                                    "AppViewModel: ⚠️ BUG: Initial paginate contains timelineRowId=0 for room $roomId. Every event should have a timelineRowId!",
                                )
                            } else {
                                // Positive value is correct - store it for pull-to-refresh
                                oldestRowIdPerRoom[roomId] = oldestInResponse
                                if (BuildConfig.DEBUG)
                                    android.util.Log.d(
                                        "Andromuks",
                                        "AppViewModel: Tracked oldest positive timelineRowId=$oldestInResponse for room $roomId from initial paginate (${timelineList.size} events, ${positiveEvents.size} positive)",
                                    )
                            }
                        } else if (positiveEvents.isEmpty()) {
                            android.util.Log.w(
                                "Andromuks",
                                "AppViewModel: ⚠️ Initial paginate for room $roomId contains no events with positive timelineRowid (all ${timelineList.size} events have non-positive rowIds)",
                            )
                        }
                    } else {
                        // For pagination requests, log the row ID range of returned events
                        val minRowId = timelineList.minByOrNull { it.timelineRowid }?.timelineRowid
                        val maxRowId = timelineList.maxByOrNull { it.timelineRowid }?.timelineRowid
                        val cacheBefore = RoomTimelineCache.getCachedEventCount(roomId)
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Pagination response - received ${timelineList.size} events with rowId range: $minRowId to $maxRowId, cache before: $cacheBefore",
                            )
                    }

                    // Populate edit chain mapping for clean edit handling using helper function
                    // CRITICAL FIX: For pagination, merge events instead of clearing to preserve
                    // newer events
                    val isPaginationRequest = paginateRequests.containsKey(requestId)
                    val isInitialPaginate = timelineRequests.containsKey(requestId)
                    // CRITICAL FIX: Only update global edit state if this is the current room
                    // Background pagination response should not corrupt current room's edit mapping
                    if (roomId == currentRoomId) {
                        buildEditChainsFromEvents(
                            timelineList,
                            clearExisting = !isPaginationRequest,
                        )

                        // Process edit relationships
                        processEditRelationships()
                    } else {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Skipping global edit chain build - roomId ($roomId) != currentRoomId ($currentRoomId)",
                            )
                    }

                    if (isPaginationRequest) {
                        // This is a pagination request - merge with existing timeline
                        // Note: handlePaginationMerge updates cache for all rooms, but guards
                        // global state
                        // updates
                        handlePaginationMerge(roomId, timelineList, requestId)
                        paginateRequests.remove(requestId)
                        // Note: paginateRequestMaxTimelineIds cleanup happens in
                        // handlePaginationMerge

                        // CRITICAL FIX: Only stop paginating indicator if currently viewing this
                        // room
                        if (roomId == currentRoomId) {
                            isPaginating = false
                        }
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Pagination complete - roomId=$roomId, isPaginating set to ${if (roomId == currentRoomId) "FALSE" else "unchanged (background)"}",
                            )
                    } else {
                        // This is an initial paginate - build timeline from chain mapping
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "🟡 handleTimelineResponse: Initial paginate - roomId=$roomId, requestId=$requestId, timelineList.size=${timelineList.size}, isTimelineLoading=$isTimelineLoading",
                            )
                        handleInitialTimelineBuild(roomId, timelineList)
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "🟡 handleTimelineResponse: After handleInitialTimelineBuild - roomId=$roomId, requestId=$requestId, timelineEvents.size=${timelineEvents.size}, isTimelineLoading=$isTimelineLoading",
                            )
                        // Clean up pending paginate tracking when initial paginate completes
                        if (isInitialPaginate) {
                            timelineRequests.remove(requestId)
                            roomsWithPendingPaginate.remove(roomId)
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "🟡 handleTimelineResponse: Cleaned up tracking - roomId=$roomId, requestId=$requestId, remaining timelineRequests=${timelineRequests.size}",
                                )
                        }

                        // CRITICAL FIX: After initial pagination completes, automatically request
                        // member
                        // profiles
                        // for all users in the timeline using get_specific_room_state
                        // This ensures room-specific display names and avatars are loaded correctly
                        if (timelineList.isNotEmpty()) {
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Initial pagination completed for $roomId, requesting member profiles for ${timelineList.size} events",
                                )
                            requestUpdatedRoomProfiles(roomId, timelineList)
                        }
                    }

                    // Mark room as read when timeline is successfully loaded - use most recent
                    // event by
                    // timestamp
                    // (But not for background prefetch requests since we're just silently updating
                    // cache)
                    // CRITICAL FIX: Only mark as read if the room is currently open (currentRoomId
                    // == roomId)
                    // This prevents notifications from being dismissed when preemptive pagination
                    // happens
                    // (preemptive pagination occurs when a notification is generated but user
                    // hasn't opened
                    // the room yet)
                    if (!backgroundPrefetchRequests.containsKey(requestId)) {
                        // First check if room is actually open - don't mark as read for preemptive
                        // pagination
                        val isRoomOpen = currentRoomId == roomId
                        if (!isRoomOpen) {
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Skipping mark as read for room $roomId (room not currently open - preemptive pagination)",
                                )
                        } else {
                            // Mark as read only if room is actually visible (not just a minimized
                            // bubble)
                            // Check if this is a bubble and if it's visible
                            val shouldMarkAsRead =
                                if (BubbleTracker.isBubbleOpen(roomId)) {
                                    // Bubble exists - only mark as read if it's visible/maximized
                                    BubbleTracker.isBubbleVisible(roomId)
                                } else {
                                    // Not a bubble - mark as read (normal room view)
                                    true
                                }

                            if (shouldMarkAsRead) {
                                // Use allEvents (includes reactions and all types) rather than
                                // timelineList (filtered to renderable types only) so that
                                // reactions or state events that arrive after the last message
                                // still produce a correct, up-to-date mark_read position.
                                val mostRecentEvent = allEvents.maxByOrNull { it.timestamp }
                                if (mostRecentEvent != null) {
                                    markRoomAsRead(roomId, mostRecentEvent.eventId)
                                }
                            } else {
                                if (BuildConfig.DEBUG)
                                    android.util.Log.d(
                                        "Andromuks",
                                        "AppViewModel: Skipping mark as read for room $roomId (bubble is minimized)",
                                    )
                            }
                        }
                    }
                }

                // Preemptive profile requests: fire before composables render so senders whose
                // profile hint was absent (displayName == null or avatarUrl == null) are fetched
                // now rather than lazily per-item. Senders with "" (confirmed absent) are skipped.
                val uniqueSenders = timelineList.mapTo(mutableSetOf()) { it.sender }
                for (sender in uniqueSenders) {
                    val profile = vm.getUserProfile(sender, roomId)
                    if (profile == null || profile.displayName == null || profile.avatarUrl == null) {
                        vm.requestUserProfileOnDemand(sender, roomId)
                    }
                }

                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "🟡 processEventsArray: COMPLETE - roomId=$roomId, requestId=$requestId, reactionsProcessed=$reactionProcessedCount, timelineList.size=${timelineList.size}, timelineEvents.size=${timelineEvents.size}, isTimelineLoading=$isTimelineLoading",
                    )
                return reactionProcessedCount
            }

            when (data) {
                is JSONArray -> {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "🟡 handleTimelineResponse: JSONArray response - roomId=$roomId, requestId=$requestId, array.length=${data.length()}",
                        )
                    totalReactionsProcessed = processEventsArray(data)
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "🟡 handleTimelineResponse: processEventsArray completed - roomId=$roomId, requestId=$requestId, reactionsProcessed=$totalReactionsProcessed, timelineEvents.size=${timelineEvents.size}, isTimelineLoading=$isTimelineLoading",
                        )
                }
                is JSONObject -> {
                    val eventsArray = data.optJSONArray("events")
                    if (eventsArray != null) {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "🟡 handleTimelineResponse: JSONObject with events array - roomId=$roomId, requestId=$requestId, events.length=${eventsArray.length()}",
                            )

                        // CRITICAL: Process related_events FIRST before processing main events
                        // This ensures that when main events are processed and rendered, the reply
                        // targets
                        // from related_events are already in the cache and can be found immediately
                        val relatedEventsArray = data.optJSONArray("related_events")
                        if (relatedEventsArray != null && relatedEventsArray.length() > 0) {
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Processing ${relatedEventsArray.length()} related_events from paginate response for room $roomId (BEFORE main events)",
                                )
                            val memberMap = RoomMemberCache.getRoomMembers(roomId)
                            RoomTimelineCache.addEventsFromSync(
                                roomId,
                                relatedEventsArray,
                                memberMap,
                            )
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Added ${relatedEventsArray.length()} related_events to timeline cache for room $roomId",
                                )
                            // CRITICAL: Increment timelineUpdateCounter so reply previews can
                            // reactively find
                            // events in related_events
                            // This ensures that when related_events are added to the cache,
                            // composables that are
                            // looking for reply targets
                            // will re-check the cache and find the newly added events
                            timelineUpdateCounter++
                        }

                        // NOW process main events array - related_events are already in cache
                        totalReactionsProcessed = processEventsArray(eventsArray)
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "🟡 handleTimelineResponse: processEventsArray completed - roomId=$roomId, requestId=$requestId, reactionsProcessed=$totalReactionsProcessed, timelineEvents.size=${timelineEvents.size}, isTimelineLoading=$isTimelineLoading",
                            )

                        // CRITICAL: Process read receipts AFTER events are fully processed
                        // This ensures that when receipts are applied, the events they reference
                        // are already in
                        // the timeline
                        // Similar to how related_events are processed BEFORE main events, receipts
                        // should be
                        // processed AFTER
                        val receipts = data.optJSONObject("receipts")
                        if (receipts != null && receipts.length() > 0) {
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Processing read receipts from paginate response for room: $roomId (AFTER events processed) - ${receipts.length()} event groups, total events in timeline: ${timelineEvents.size}",
                                )
                            // Process receipts in background for parsing, but ensure events are
                            // already processed
                            viewModelScope.launch(Dispatchers.Default) {
                                try {
                                    // Parse receipts data in background (extract changes)
                                    val receiptsCopy =
                                        receipts.toString() // Deep copy JSON to avoid thread issues
                                    val parsedReceipts = org.json.JSONObject(receiptsCopy)

                                    // Build the authoritative receipts map in background (no
                                    // reading from
                                    // readReceipts)
                                    // PAGINATE IS AUTHORITATIVE: Accept all receipts as-is from the
                                    // server, no
                                    // deduplication needed
                                    // The server's paginate response is the source of truth - if it
                                    // says event X has
                                    // 6 receipts, we show 6 receipts
                                    val authoritativeReceipts =
                                        mutableMapOf<String, MutableList<ReadReceipt>>()
                                    val receiptUserIds = mutableSetOf<String>()
                                    val keys = parsedReceipts.keys()

                                    while (keys.hasNext()) {
                                        val eventId = keys.next()
                                        val receiptsArray = parsedReceipts.optJSONArray(eventId)
                                        if (receiptsArray != null) {
                                            val receiptsForEvent = mutableListOf<ReadReceipt>()

                                            for (i in 0 until receiptsArray.length()) {
                                                val receiptJson = receiptsArray.optJSONObject(i)
                                                if (receiptJson != null) {
                                                    val receipt =
                                                        ReadReceipt(
                                                            userId =
                                                                receiptJson.optString(
                                                                    "user_id",
                                                                    "",
                                                                ),
                                                            eventId =
                                                                receiptJson.optString(
                                                                    "event_id",
                                                                    "",
                                                                ),
                                                            timestamp =
                                                                receiptJson.optLong("timestamp", 0),
                                                            receiptType =
                                                                receiptJson.optString(
                                                                    "receipt_type",
                                                                    "",
                                                                ),
                                                            roomId = roomId, // Store room ID for
                                                            // consistency (paginate is
                                                            // authoritative but roomId helps with
                                                            // filtering)
                                                        )

                                                    // Validate receipt has required fields and
                                                    // eventId matches
                                                    if (
                                                        receipt.userId.isNotBlank() &&
                                                            receipt.eventId.isNotBlank() &&
                                                            receipt.eventId == eventId
                                                    ) {
                                                        receiptsForEvent.add(receipt)
                                                        receiptUserIds.add(receipt.userId)
                                                    } else {
                                                        if (BuildConfig.DEBUG)
                                                            android.util.Log.w(
                                                                "Andromuks",
                                                                "AppViewModel: Invalid receipt - eventId=$eventId, receiptEventId=${receipt.eventId}, userId=${receipt.userId}, roomId=$roomId",
                                                            )
                                                    }
                                                }
                                            }

                                            // Store all receipts for this event
                                            authoritativeReceipts[eventId] = receiptsForEvent
                                        } else {
                                            // Event has no receipts array - mark as empty (remove
                                            // existing receipts)
                                            authoritativeReceipts[eventId] = mutableListOf()
                                            if (BuildConfig.DEBUG)
                                                android.util.Log.d(
                                                    "Andromuks",
                                                    "AppViewModel: Event $eventId has no receipts array - marking as empty",
                                                )
                                        }
                                    }

                                    // Remap any receipts that landed on bridge status event IDs to
                                    // their original message event IDs. Bridge bots send m.read
                                    // receipts for their own com.beeper.message_send_status events,
                                    // not for the original message, so those receipts would otherwise
                                    // be invisible (status events never appear in the timeline).
                                    if (vm.bridgeStatusEventToMessageId.isNotEmpty()) {
                                        val remapEntries = authoritativeReceipts.entries
                                            .filter { vm.bridgeStatusEventToMessageId.containsKey(it.key) }
                                            .toList()
                                        for ((statusEventId, receipts) in remapEntries) {
                                            val originalMessageId = vm.bridgeStatusEventToMessageId[statusEventId] ?: continue
                                            authoritativeReceipts.remove(statusEventId)
                                            if (receipts.isNotEmpty()) {
                                                val remapped = receipts.map { r -> r.copy(eventId = originalMessageId) }
                                                val existing = authoritativeReceipts.getOrPut(originalMessageId) { mutableListOf() }
                                                remapped.forEach { r ->
                                                    if (existing.none { it.userId == r.userId }) existing.add(r)
                                                }
                                                if (BuildConfig.DEBUG)
                                                    android.util.Log.d("Andromuks", "BridgeReceipt: remapped ${receipts.size} receipt(s) from status event $statusEventId → $originalMessageId")
                                            }
                                        }
                                    }

                                    val totalReceipts =
                                        authoritativeReceipts.values.sumOf { it.size }
                                    if (BuildConfig.DEBUG)
                                        android.util.Log.d(
                                            "Andromuks",
                                            "AppViewModel: Processed $totalReceipts total receipts from paginate, distributed across ${authoritativeReceipts.size} events",
                                        )

                                    // Apply changes on main thread to avoid concurrent modification
                                    // during
                                    // composition
                                    withContext(Dispatchers.Main) {
                                        try {
                                            var hasChanges = false

                                            synchronized(readReceiptsLock) {
                                                // Apply all changes atomically on main thread
                                                authoritativeReceipts.forEach { (eventId, receipts)
                                                    ->
                                                    val existingReceipts = readReceipts[eventId]
                                                    // CRITICAL FIX: Include roomId in comparison to
                                                    // properly detect changes
                                                    // Old receipts might have empty roomId, new
                                                    // ones have roomId set
                                                    val receiptsChanged =
                                                        existingReceipts == null ||
                                                            existingReceipts.size !=
                                                                receipts.size ||
                                                            existingReceipts.any { existing ->
                                                                !receipts.any { auth ->
                                                                    auth.userId ==
                                                                        existing.userId &&
                                                                        auth.timestamp ==
                                                                            existing.timestamp &&
                                                                        auth.eventId ==
                                                                            existing.eventId &&
                                                                        auth.roomId ==
                                                                            existing
                                                                                .roomId // Compare
                                                                    // roomId
                                                                }
                                                            } ||
                                                            receipts.any { auth ->
                                                                !existingReceipts.any { existing ->
                                                                    existing.userId ==
                                                                        auth.userId &&
                                                                        existing.timestamp ==
                                                                            auth.timestamp &&
                                                                        existing.eventId ==
                                                                            auth.eventId &&
                                                                        existing.roomId ==
                                                                            auth.roomId // Compare
                                                                    // roomId
                                                                }
                                                            }

                                                    if (receiptsChanged) {
                                                        if (receipts.isEmpty()) {
                                                            readReceipts.remove(eventId)
                                                            if (BuildConfig.DEBUG)
                                                                android.util.Log.d(
                                                                    "Andromuks",
                                                                    "ReceiptFunctions: Removed all receipts for eventId=$eventId, roomId=$roomId (server says none)",
                                                                )
                                                        } else {
                                                            readReceipts[eventId] = receipts

                                                            // (3) IMPLICIT DELIVERY: Receipt in paginate implies delivery
                                                            if (messageBridgeSendStatus.containsKey(eventId)) {
                                                                updateBridgeStatus(eventId, "delivered")
                                                            }

                                                            if (BuildConfig.DEBUG)
                                                                android.util.Log.d(
                                                                    "Andromuks",
                                                                    "ReceiptFunctions: Replaced receipts for eventId=$eventId, roomId=$roomId with ${receipts.size} receipts from paginate",
                                                                )
                                                        }
                                                        hasChanges = true
                                                    }
                                                }

                                                // Update singleton cache after processing receipts
                                                // (only if there were
                                                // changes)
                                                if (hasChanges) {
                                                    val receiptsForCache =
                                                        readReceipts.mapValues { it.value.toList() }
                                                    if (BuildConfig.DEBUG)
                                                        android.util.Log.d(
                                                            "Andromuks",
                                                            "AppViewModel: Updating ReadReceiptCache with ${receiptsForCache.size} events (${receiptsForCache.values.sumOf { it.size }} total receipts) from paginate for room: $roomId",
                                                        )
                                                    ReadReceiptCache.setAll(receiptsForCache)
                                                    if (BuildConfig.DEBUG) {
                                                        val cacheAfter =
                                                            ReadReceiptCache.getAllReceipts()
                                                        android.util.Log.d(
                                                            "Andromuks",
                                                            "AppViewModel: ReadReceiptCache after update: ${cacheAfter.size} events (${cacheAfter.values.sumOf { it.size }} total receipts)",
                                                        )
                                                    }
                                                }
                                            }

                                            // Single UI update after all processing (only if there
                                            // were changes)
                                            if (hasChanges) {
                                                readReceiptsUpdateCounter++
                                            }

                                            // Preemptive profile requests for receipt holders:
                                            // fire before composables render their avatars.
                                            // Only request where displayName or avatarUrl is still
                                            // null (unknown); "" means confirmed absent, skip those.
                                            for (userId in receiptUserIds) {
                                                val profile = vm.getUserProfile(userId, roomId)
                                                if (profile == null || profile.displayName == null || profile.avatarUrl == null) {
                                                    vm.requestUserProfileOnDemand(userId, roomId)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e(
                                                "Andromuks",
                                                "AppViewModel: Error updating receipt state on main thread",
                                                e,
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e(
                                        "Andromuks",
                                        "AppViewModel: Error processing receipts in background",
                                        e,
                                    )
                                }
                            }
                        } else {
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: No receipts found in paginate response for room: $roomId",
                                )
                        }

                        // After processing all events, check for missing m.in_reply_to targets
                        // If a message references a reply target that's not in the cache, fetch it
                        // via
                        // get_event
                        viewModelScope.launch(Dispatchers.Default) {
                            try {
                                // Re-check cache after adding related_events to ensure we have the
                                // latest state
                                val cachedEvents =
                                    RoomTimelineCache.getCachedEvents(roomId) ?: emptyList()
                                val cachedEventIds = cachedEvents.map { it.eventId }.toSet()
                                val missingReplyTargets =
                                    mutableSetOf<Pair<String, String>>() // (roomId, eventId)

                                // Check all events in the response for m.in_reply_to references
                                for (i in 0 until eventsArray.length()) {
                                    val eventJson = eventsArray.optJSONObject(i) ?: continue
                                    val event = TimelineEvent.fromJson(eventJson)

                                    // Check for m.in_reply_to in both content and decrypted
                                    val replyToEventId =
                                        event.content
                                            ?.optJSONObject("m.relates_to")
                                            ?.optJSONObject("m.in_reply_to")
                                            ?.optString("event_id")
                                            ?: event.decrypted
                                                ?.optJSONObject("m.relates_to")
                                                ?.optJSONObject("m.in_reply_to")
                                                ?.optString("event_id")

                                    if (replyToEventId != null && replyToEventId.isNotBlank()) {
                                        // Check if the reply target is in the cache
                                        if (!cachedEventIds.contains(replyToEventId)) {
                                            missingReplyTargets.add(
                                                Pair(event.roomId, replyToEventId)
                                            )
                                            if (BuildConfig.DEBUG)
                                                android.util.Log.d(
                                                    "Andromuks",
                                                    "AppViewModel: Missing reply target event_id=$replyToEventId for event ${event.eventId} in room ${event.roomId}",
                                                )
                                        }
                                    }
                                }

                                // Also check related_events for m.in_reply_to references
                                if (relatedEventsArray != null) {
                                    for (i in 0 until relatedEventsArray.length()) {
                                        val eventJson =
                                            relatedEventsArray.optJSONObject(i) ?: continue
                                        val event = TimelineEvent.fromJson(eventJson)

                                        val replyToEventId =
                                            event.content
                                                ?.optJSONObject("m.relates_to")
                                                ?.optJSONObject("m.in_reply_to")
                                                ?.optString("event_id")
                                                ?: event.decrypted
                                                    ?.optJSONObject("m.relates_to")
                                                    ?.optJSONObject("m.in_reply_to")
                                                    ?.optString("event_id")

                                        if (replyToEventId != null && replyToEventId.isNotBlank()) {
                                            if (!cachedEventIds.contains(replyToEventId)) {
                                                missingReplyTargets.add(
                                                    Pair(event.roomId, replyToEventId)
                                                )
                                                if (BuildConfig.DEBUG)
                                                    android.util.Log.d(
                                                        "Andromuks",
                                                        "AppViewModel: Missing reply target event_id=$replyToEventId for related event ${event.eventId} in room ${event.roomId}",
                                                    )
                                            }
                                        }
                                    }
                                }

                                // Fetch missing reply targets via get_event
                                if (missingReplyTargets.isNotEmpty()) {
                                    if (BuildConfig.DEBUG)
                                        android.util.Log.d(
                                            "Andromuks",
                                            "AppViewModel: Fetching ${missingReplyTargets.size} missing reply target events via get_event",
                                        )

                                    for ((targetRoomId, targetEventId) in missingReplyTargets) {
                                        // Use a suspend function to fetch the event
                                        val deferred = CompletableDeferred<TimelineEvent?>()
                                        withContext(Dispatchers.Main) {
                                            getEvent(targetRoomId, targetEventId) { event ->
                                                deferred.complete(event)
                                            }
                                        }

                                        val fetchedEvent =
                                            withTimeoutOrNull(5000L) { deferred.await() }

                                        if (fetchedEvent != null) {
                                            // Add the fetched event to the cache
                                            val memberMap =
                                                RoomMemberCache.getRoomMembers(targetRoomId)
                                            val eventsJsonArray = org.json.JSONArray()
                                            eventsJsonArray.put(fetchedEvent.toRawJsonObject())
                                            RoomTimelineCache.addEventsFromSync(
                                                targetRoomId,
                                                eventsJsonArray,
                                                memberMap,
                                            )
                                            if (BuildConfig.DEBUG)
                                                android.util.Log.d(
                                                    "Andromuks",
                                                    "AppViewModel: Fetched and cached missing reply target event_id=$targetEventId for room $targetRoomId",
                                                )
                                        } else {
                                            android.util.Log.w(
                                                "Andromuks",
                                                "AppViewModel: Failed to fetch missing reply target event_id=$targetEventId for room $targetRoomId (timeout or error)",
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(
                                    "Andromuks",
                                    "AppViewModel: Error checking for missing reply targets",
                                    e,
                                )
                            }
                        }
                    } else {
                        android.util.Log.w(
                            "Andromuks",
                            "🟡 handleTimelineResponse: JSONObject did not contain 'events' array - roomId=$roomId, requestId=$requestId, keys=${data.keys().asSequence().toList()}",
                        )
                    }

                    // Parse has_more field for pagination (but not for background prefetch)
                    // NOTE: has_more is already parsed above for empty responses, but parse it here
                    // too
                    // for non-empty responses to ensure consistency
                    if (paginateRequests.containsKey(requestId)) {
                        val hasMore =
                            hasMoreFromResponse
                                ?: data.optBoolean(
                                    "has_more",
                                    true,
                                ) // Use pre-parsed value if available, otherwise parse
                        val fromServer = data.optBoolean("from_server", false)

                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: ========================================",
                            )
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: PARSING PAGINATION METADATA",
                            )
                        if (BuildConfig.DEBUG)
                            android.util.Log.d("Andromuks", "AppViewModel:    has_more: $hasMore")
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel:    from_server: $fromServer",
                            )
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel:    hasMoreMessages BEFORE: $hasMoreMessages",
                            )

                        hasMoreMessages = hasMore

                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel:    hasMoreMessages AFTER: $hasMoreMessages",
                            )
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Full pagination response data keys: ${data.keys().asSequence().toList()}",
                            )
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: ========================================",
                            )

                        // Show toast when reaching the end (empty responses already handled in
                        // processEventsArray)
                        if (!hasMore) {
                            android.util.Log.w(
                                "Andromuks",
                                "AppViewModel: 🏁 REACHED END OF MESSAGE HISTORY (has_more=false)",
                            )
                            // Toast for empty responses is already shown in processEventsArray, so
                            // only show for
                            // non-empty responses here
                            // We can't easily check if response was empty here, so we'll show toast
                            // regardless
                            // (it's idempotent)
                            appContext?.let { context ->
                                android.widget.Toast.makeText(
                                        context,
                                        "No more messages available",
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                        }
                    } else if (backgroundPrefetchRequests.containsKey(requestId)) {
                        // For background prefetch, we don't update hasMoreMessages to avoid
                        // affecting UI
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Skipping has_more parsing for background prefetch request",
                            )
                    }

                    // NOTE: Receipts are now processed AFTER events (see above, after
                    // processEventsArray
                    // completes)
                    // This ensures events are in the timeline before receipts are applied
                }
                else -> {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Unhandled data type in handleTimelineResponse: ${data::class.java.simpleName}",
                        )
                }
            }

            // IMPORTANT: If we processed reactions in background prefetch, trigger UI update
            if (isBackgroundPrefetchRequest && totalReactionsProcessed > 0) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Triggering UI update for $totalReactionsProcessed reactions processed in background prefetch",
                    )
                reactionUpdateCounter++ // Trigger UI recomposition for reactions
            }

            val roomIdFromTimeline = timelineRequests.remove(requestId)
            if (roomIdFromTimeline != null) {
                roomsWithPendingPaginate.remove(roomIdFromTimeline)
            }
            paginateRequests.remove(requestId)
            paginateRequestMaxTimelineIds.remove(requestId)
            backgroundPrefetchRequests.remove(requestId)
        }
    }

    fun mergePaginationEvents(newEvents: List<TimelineEvent>) {
        with(vm) {
            if (newEvents.isEmpty()) {
                return
            }

            // Update version caches with newly loaded events (edits, redactions, originals)
            processVersionedMessages(newEvents)

            // Integrate the new events into the edit-chain structures so buildTimelineFromChain()
            // can regenerate the final timeline with the latest edits applied.
            for (event in newEvents) {
                when {
                    isEditEvent(event) -> {
                        editEventsMap[event.eventId] = event
                    }
                    else -> {
                        val existingEntry = eventChainMap[event.eventId]
                        if (existingEntry == null) {
                            eventChainMap[event.eventId] =
                                AppViewModel.EventChainEntry(
                                    eventId = event.eventId,
                                    ourBubble = event,
                                    replacedBy = null,
                                    originalTimestamp = event.timestamp,
                                )
                        } else if (existingEntry.ourBubble == null) {
                            eventChainMap[event.eventId] = existingEntry.copy(ourBubble = event)
                        }

                        // Handle redaction events (both encrypted and non-encrypted for E2EE rooms)
                        val isRedaction =
                            event.type == "m.room.redaction" ||
                                (event.type == "m.room.encrypted" &&
                                    event.decryptedType == "m.room.redaction")

                        if (isRedaction) {
                            // For encrypted redactions, check decrypted content; for non-encrypted,
                            // check content
                            val redactsEventId =
                                when {
                                    event.type == "m.room.encrypted" &&
                                        event.decryptedType == "m.room.redaction" -> {
                                        event.decrypted?.optString("redacts")?.takeIf {
                                            it.isNotBlank()
                                        }
                                    }
                                    else -> {
                                        event.content?.optString("redacts")?.takeIf {
                                            it.isNotBlank()
                                        }
                                    }
                                }
                            if (redactsEventId != null) {
                                val versioned = messageVersions[redactsEventId]
                                if (versioned != null) {
                                    MessageVersionsCache.updateVersion(
                                        redactsEventId,
                                        versioned.copy(
                                            redactedBy = event.eventId,
                                            redactionEvent = event,
                                        ),
                                    )
                                } else {
                                    // Redaction came before the original event - try to find
                                    // original in cache
                                    // This happens when pagination returns redaction before the
                                    // original message
                                    val originalEvent =
                                        RoomTimelineCache.getCachedEvents(event.roomId)?.find {
                                            it.eventId == redactsEventId
                                        }

                                    if (originalEvent != null) {
                                        // Found original event in cache - create VersionedMessage
                                        // with redaction
                                        MessageVersionsCache.updateVersion(
                                            redactsEventId,
                                            VersionedMessage(
                                                originalEventId = redactsEventId,
                                                originalEvent = originalEvent,
                                                versions = emptyList(),
                                                redactedBy = event.eventId,
                                                redactionEvent = event,
                                            ),
                                        )
                                        if (BuildConfig.DEBUG)
                                            android.util.Log.d(
                                                "Andromuks",
                                                "AppViewModel: mergePaginationEvents - Redaction event ${event.eventId} received before original $redactsEventId, but found original in cache",
                                            )
                                    } else {
                                        // Original not in cache yet - create placeholder so
                                        // redaction can be found
                                        // We'll use the redaction event itself as a temporary
                                        // originalEvent
                                        // This will be updated when the original event arrives
                                        MessageVersionsCache.updateVersion(
                                            redactsEventId,
                                            VersionedMessage(
                                                originalEventId = redactsEventId,
                                                originalEvent = event, // Temporary placeholder
                                                versions = emptyList(),
                                                redactedBy = event.eventId,
                                                redactionEvent = event,
                                            ),
                                        )
                                        if (BuildConfig.DEBUG)
                                            android.util.Log.d(
                                                "Andromuks",
                                                "AppViewModel: mergePaginationEvents - Redaction event ${event.eventId} received before original $redactsEventId - created placeholder",
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            processEditRelationships()
            buildTimelineFromChain()
        }
    }

    fun handleBackgroundPrefetch(roomId: String, timelineList: List<TimelineEvent>): Int {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Processing background prefetch request, silently adding ${timelineList.size} events to cache (roomId: $roomId)",
                )
            RoomTimelineCache.mergePaginatedEvents(roomId, timelineList)

            // No local persistence - using cache only
            signalRoomSnapshotReady(roomId)

            val newCacheCount = RoomTimelineCache.getCachedEventCount(roomId)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ✅ Background prefetch completed - cache now has $newCacheCount events for room $roomId",
                )

            // CRITICAL FIX: Only update smallestRowId if this room is currently open
            // smallestRowId is a global variable that affects the currently open room's pagination
            // Updating it for a background prefetch of a different room would break pagination for
            // the
            // open room
            if (currentRoomId == roomId) {
                // Room is currently open — rebuild timeline from merged cache so the UI
                // seamlessly grows from the few cached events to the full paginate response.
                // This is the "Option B" approach: show cache immediately, merge paginate in
                // background, rebuild once.  No wipe, no flash.
                val mergedEvents = RoomTimelineCache.getCachedEventsForTimeline(roomId)
                if (mergedEvents.isNotEmpty()) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Background prefetch for OPEN room $roomId — rebuilding timeline from ${mergedEvents.size} merged events (${timelineList.size} new from paginate)",
                    )
                    processCachedEvents(roomId, mergedEvents, openingFromNotification = false)

                    // Track oldest POSITIVE rowId from the paginate response for pull-to-refresh
                    // CRITICAL: Only use positive timelineRowid values for pagination (negative
                    // values are
                    // for state events)
                    val positiveEvents = timelineList.filter { it.timelineRowid != 0L }
                    val oldestInResponse = positiveEvents.minOfOrNull { it.timelineRowid }
                    if (oldestInResponse != null && oldestInResponse != 0L) {
                        oldestRowIdPerRoom[roomId] = oldestInResponse
                    }
                }
                smallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
                roomsWithPendingPaginate.remove(roomId)
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Updated smallestRowId=$smallestRowId for background prefetch (room is currently open, timeline rebuilt)",
                    )
            } else {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Skipped updating smallestRowId for background prefetch (room $roomId is not currently open, currentRoomId=$currentRoomId)",
                    )
            }
            return 0 // No reactions processed
        }
    }

    fun handlePaginationMerge(roomId: String, timelineList: List<TimelineEvent>, requestId: Int) {
        with(vm) {
            if (BuildConfig.DEBUG) {
                // Extra safety: detect any events whose roomId doesn't match the target room.
                val mismatched = timelineList.filter { it.roomId != roomId }
                if (mismatched.isNotEmpty()) {
                    val distinctRooms =
                        mismatched.asSequence().map { it.roomId }.distinct().take(5).toList()
                    android.util.Log.e(
                        "Andromuks",
                        "AppViewModel: ⚠️ Mismatched room_id in pagination merge for room $roomId, events from rooms: $distinctRooms (count=${mismatched.size})",
                    )
                    appContext?.let { context ->
                        android.widget.Toast.makeText(
                                context,
                                "Debug: Dropped ${mismatched.size} events with wrong room_id for room $roomId",
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
            }
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ========================================",
                )
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: PAGINATION RESPONSE RECEIVED (requestId: $requestId)",
                )
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Received ${timelineList.size} events from backend",
                )
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Timeline events BEFORE merge: ${timelineEvents.size}",
                )
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Cache BEFORE merge: ${RoomTimelineCache.getCachedEventCount(roomId)} events",
                )

            val cacheBefore = RoomTimelineCache.getCachedEventCount(roomId)
            val oldestRowIdBefore = RoomTimelineCache.getOldestCachedEventRowId(roomId)

            // CRITICAL FIX: Validate pagination response before merging
            // Backend should only return events with timeline_rowid < max_timeline_id
            val maxTimelineIdUsed = paginateRequestMaxTimelineIds[requestId]
            val eventsToMerge =
                if (
                    maxTimelineIdUsed != null &&
                        maxTimelineIdUsed != 0L &&
                        timelineList.isNotEmpty()
                ) {
                    val invalidEvents =
                        timelineList.filter { it.timelineRowid >= maxTimelineIdUsed }
                    if (invalidEvents.isNotEmpty()) {
                        android.util.Log.e(
                            "Andromuks",
                            "⚠️ PAGINATION BUG: Backend returned ${invalidEvents.size} events with rowId >= max_timeline_id ($maxTimelineIdUsed)! Filtering them out.",
                        )
                        invalidEvents.take(5).forEach { event ->
                            android.util.Log.e(
                                "Andromuks",
                                "  - Invalid event ${event.eventId}: rowId=${event.timelineRowid}, timestamp=${event.timestamp}",
                            )
                        }
                        // Filter out invalid events before merging
                        val validEvents =
                            timelineList.filter { it.timelineRowid < maxTimelineIdUsed }
                        if (validEvents.size != timelineList.size) {
                            android.util.Log.w(
                                "Andromuks",
                                "Filtered ${timelineList.size - validEvents.size} invalid events from pagination response",
                            )
                        }
                        validEvents
                    } else {
                        timelineList
                    }
                } else {
                    timelineList
                }

            val eventsAdded = RoomTimelineCache.mergePaginatedEvents(roomId, eventsToMerge)

            // CRITICAL FIX: After adding events to cache, reload ALL events from cache into
            // eventChainMap
            // This ensures the timeline reflects all cached events, not just the newly paginated
            // ones
            // Without this, eventChainMap might be missing events that were in the cache but not in
            // eventChainMap
            val eventsForChain = RoomTimelineCache.getCachedEventsForTimeline(roomId)
            if (eventsForChain.isNotEmpty()) {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Reloading ${eventsForChain.size} events from cache into eventChainMap after pagination",
                    )

                // CRITICAL FIX: Only update global state (eventChainMap, editEventsMap,
                // timelineEvents) if
                // this is the current room
                if (roomId == currentRoomId) {
                    // Clear and rebuild eventChainMap from all cached events
                    // This ensures we have all events, not just the ones that were in eventChainMap
                    // before
                    eventChainMap.clear()
                    editEventsMap.clear()

                    for (event in eventsForChain) {
                        val isEdit = isEditEvent(event)
                        if (isEdit) {
                            editEventsMap[event.eventId] = event
                        } else {
                            eventChainMap[event.eventId] =
                                AppViewModel.EventChainEntry(
                                    eventId = event.eventId,
                                    ourBubble = event,
                                    replacedBy = null,
                                    originalTimestamp = event.timestamp,
                                )
                        }
                    }

                    // Process versioned messages and edit relationships (main events only for
                    // version
                    // tracking)
                    processVersionedMessages(
                        RoomTimelineCache.getCachedEvents(roomId) ?: emptyList()
                    )
                    processEditRelationships()

                    // Rebuild timeline from all cached events
                    buildTimelineFromChain()
                } else {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Skipping eventChainMap rebuild - roomId ($roomId) != currentRoomId ($currentRoomId). Cache merged only.",
                        )
                }
            } else {
                // Fallback: if we can't get all cached events, just merge the new ones
                // Only if it's the current room. Otherwise skip.
                if (roomId == currentRoomId) {
                    mergePaginationEvents(timelineList)
                }
            }

            val oldestRowIdAfter = RoomTimelineCache.getOldestCachedEventRowId(roomId)

            // CRITICAL FIX: Detect when pagination makes no progress by comparing request's
            // max_timeline_id to response
            // val maxTimelineIdUsed = paginateRequestMaxTimelineIds[requestId]
            paginateRequestMaxTimelineIds.remove(requestId) // Clean up tracking

            // Log paginate response with earliest and oldest timeline_rowid
            if (timelineList.isNotEmpty()) {
                val earliestTimelineRowId = timelineList.minOfOrNull { it.timelineRowid } ?: -1L
                val oldestTimelineRowId = timelineList.maxOfOrNull { it.timelineRowid } ?: -1L
                val earliestTimestamp = timelineList.minOfOrNull { it.timestamp } ?: -1L
                val oldestTimestamp = timelineList.maxOfOrNull { it.timestamp } ?: -1L
                android.util.Log.d(
                    "Andromuks",
                    "paginate response: Room - $roomId, Earliest timeline_rowid - $earliestTimelineRowId, Oldest timeline_rowid - $oldestTimelineRowId, Events - ${timelineList.size}",
                )
                android.util.Log.d(
                    "Andromuks",
                    "paginate response: Room - $roomId, Earliest timestamp - $earliestTimestamp, Oldest timestamp - $oldestTimestamp, max_timeline_id used - $maxTimelineIdUsed",
                )

                // CRITICAL DEBUG: Check if response contains events NEWER than max_timeline_id
                // (shouldn't
                // happen!)
                val eventsNewerThanMax =
                    timelineList.filter {
                        it.timelineRowid >= (maxTimelineIdUsed ?: Long.MAX_VALUE)
                    }
                if (eventsNewerThanMax.isNotEmpty()) {
                    android.util.Log.e(
                        "Andromuks",
                        "⚠️ PAGINATION BUG: Response contains ${eventsNewerThanMax.size} events with rowId >= max_timeline_id ($maxTimelineIdUsed)! These should not be returned!",
                    )
                    eventsNewerThanMax.take(5).forEach { event ->
                        android.util.Log.e(
                            "Andromuks",
                            "  - Event ${event.eventId}: rowId=${event.timelineRowid}, timestamp=${event.timestamp}",
                        )
                    }
                }
            } else {
                android.util.Log.d(
                    "Andromuks",
                    "paginate response: Room - $roomId, No events returned",
                )
            }

            // Track the oldest POSITIVE timelineRowId from this pagination response
            // The oldest event will have the lowest (smallest) POSITIVE timelineRowId in the
            // response
            // CRITICAL: Only use positive timelineRowid values for pagination (negative values are
            // for
            // state events)
            // This matches Webmucks backend behavior: pagination uses positive timeline_rowid
            // values only
            if (timelineList.isNotEmpty()) {
                // Filter to only positive timelineRowids (pagination events only, exclude state
                // events)
                val positiveEvents = timelineList.filter { it.timelineRowid != 0L }
                val oldestInResponse = positiveEvents.minOfOrNull { it.timelineRowid }

                if (oldestInResponse != null) {
                    // timelineRowId of 0 is a bug (should never happen)
                    if (oldestInResponse == 0L) {
                        android.util.Log.e(
                            "Andromuks",
                            "AppViewModel: ⚠️ BUG: Pagination response contains timelineRowId=0 for room $roomId. Every event should have a timelineRowId!",
                        )
                    } else {
                        // CRITICAL FIX: Detect when we're making no progress (response's oldest
                        // event >=
                        // max_timeline_id we used)
                        // This means we got the same or newer events, not older ones - we're stuck!
                        if (maxTimelineIdUsed != null && oldestInResponse >= maxTimelineIdUsed) {
                            android.util.Log.w(
                                "Andromuks",
                                "AppViewModel: ⚠️ PAGINATION STUCK: Response's oldest event ($oldestInResponse) >= max_timeline_id used ($maxTimelineIdUsed). No progress made!",
                            )
                            // If we got duplicates and made no progress, stop pagination to prevent
                            // infinite loop
                            if (eventsAdded == 0) {
                                android.util.Log.w(
                                    "Andromuks",
                                    "AppViewModel: Setting hasMoreMessages=false to prevent infinite pagination loop (no progress + duplicates)",
                                )
                                hasMoreMessages = false
                                appContext?.let { context ->
                                    android.widget.Toast.makeText(
                                            context,
                                            "No more messages to load",
                                            android.widget.Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }
                            }
                        } else {
                            // Store it for next pull-to-refresh (only positive values)
                            oldestRowIdPerRoom[roomId] = oldestInResponse
                            if (BuildConfig.DEBUG)
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Tracked oldest positive timelineRowId=$oldestInResponse for room $roomId (from ${timelineList.size} events, ${positiveEvents.size} positive, max_timeline_id used was $maxTimelineIdUsed)",
                                )
                        }
                    }
                } else if (positiveEvents.isEmpty()) {
                    // No positive timelineRowids in response - this is unusual but not necessarily
                    // an error
                    // (could be all state events, though pagination should return timeline events)
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: ⚠️ Pagination response for room $roomId contains no events with positive timelineRowid (all ${timelineList.size} events have non-positive rowIds)",
                    )
                }
            }

            // FIX: If all events were duplicates (eventsAdded == 0), we need to handle this case
            // If we got events but none were added, and the oldestRowId didn't change, it means
            // we're stuck requesting the same range. We should set hasMoreMessages = false to
            // prevent
            // infinite pagination loops, unless the backend explicitly says has_more = false (which
            // is already handled). However, if backend says has_more = true but we got no new
            // events,
            // we should still respect that and let the next pagination try with a different
            // max_timeline_id.
            if (eventsAdded == 0 && timelineList.isNotEmpty()) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: ⚠️ Pagination returned ${timelineList.size} events but all were duplicates (oldestRowId: $oldestRowIdBefore -> $oldestRowIdAfter)",
                )
                // If oldestRowId didn't change, we made no progress - this could indicate we've
                // reached the
                // end
                // or that we need to adjust our pagination strategy. For now, we'll let the
                // backend's
                // has_more
                // flag control this, but log a warning.
                if (oldestRowIdBefore == oldestRowIdAfter && oldestRowIdBefore != -1L) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: ⚠️ No progress made in pagination (oldestRowId unchanged). If this persists, consider setting hasMoreMessages = false.",
                    )
                }
            }

            // No local persistence - using cache only

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Timeline events AFTER merge: ${timelineEvents.size}",
                )
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Cache AFTER merge: ${RoomTimelineCache.getCachedEventCount(roomId)} events",
                )

            val newSmallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: smallestRowId BEFORE: $smallestRowId",
                )
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: smallestRowId AFTER: $newSmallestRowId",
                )
            smallestRowId = newSmallestRowId

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ========================================",
                )
        }
    }

    fun handleInitialTimelineBuild(roomId: String, timelineList: List<TimelineEvent>) {
        with(vm) {
            if (BuildConfig.DEBUG) {
                val mismatched = timelineList.filter { it.roomId != roomId }
                if (mismatched.isNotEmpty()) {
                    val distinctRooms =
                        mismatched.asSequence().map { it.roomId }.distinct().take(5).toList()
                    android.util.Log.e(
                        "Andromuks",
                        "AppViewModel: ⚠️ Mismatched room_id in initial timeline build for room $roomId, events from rooms: $distinctRooms (count=${mismatched.size})",
                    )
                    appContext?.let { context ->
                        android.widget.Toast.makeText(
                                context,
                                "Debug: Dropped ${mismatched.size} events with wrong room_id for room $roomId",
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
            }
            // Track the oldest POSITIVE timelineRowId from initial pagination response
            // This is the lowest (smallest) POSITIVE timelineRowId, which represents the oldest
            // timeline
            // event
            // CRITICAL: Only use positive timelineRowid values for pagination (negative values are
            // for
            // state events)
            // This matches Webmucks backend behavior: pagination uses positive timeline_rowid
            // values only
            if (timelineList.isNotEmpty()) {
                // Filter to only positive timelineRowids (pagination events only, exclude state
                // events)
                val positiveEvents = timelineList.filter { it.timelineRowid != 0L }
                val oldestInResponse = positiveEvents.minOfOrNull { it.timelineRowid }

                if (oldestInResponse != null) {
                    // timelineRowId of 0 is a bug (should never happen)
                    if (oldestInResponse == 0L) {
                        android.util.Log.e(
                            "Andromuks",
                            "AppViewModel: ⚠️ BUG: Initial paginate contains timelineRowId=0 for room $roomId. Every event should have a timelineRowId!",
                        )
                    } else {
                        // Store it for pull-to-refresh (only positive values)
                        oldestRowIdPerRoom[roomId] = oldestInResponse
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Tracked oldest positive timelineRowId=$oldestInResponse for room $roomId from initial paginate in handleInitialTimelineBuild (${timelineList.size} events, ${positiveEvents.size} positive)",
                            )
                    }
                } else if (positiveEvents.isEmpty()) {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: ⚠️ Initial paginate in handleInitialTimelineBuild for room $roomId contains no events with positive timelineRowid (all ${timelineList.size} events have non-positive rowIds)",
                    )
                }
            }

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Seeding cache with ${timelineList.size} paginated events for room $roomId",
                )
            RoomTimelineCache.seedCacheWithPaginatedEvents(roomId, timelineList)

            // CRITICAL FIX: Restore reactions from cache and apply aggregated reactions from events
            // This ensures reactions are visible when paginate response rebuilds the timeline
            // Force reload to ensure reactions are loaded even if they were loaded earlier from
            // cache
            // Note: messageReactions is a global map keyed by eventId, so it's safe to update even
            // for
            // background rooms
            loadReactionsForRoom(roomId, timelineList, forceReload = true)
            applyAggregatedReactionsFromEvents(timelineList, "handleInitialTimelineBuild")

            // CRITICAL FIX: Only update timeline state if this is the currently open room
            // This prevents race conditions where a background pagination for a previous room
            // clobbers the timeline of the current room or corrupts loading state.
            if (roomId == currentRoomId) {
                buildTimelineFromChain()
                val timelineSizeBefore = timelineEvents.size
                isTimelineLoading = false
                val timelineSizeAfter = timelineEvents.size
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "🟡 handleInitialTimelineBuild: Timeline built - roomId=$roomId, timelineList.size=${timelineList.size}, timelineEvents.size=$timelineSizeAfter (was $timelineSizeBefore), isTimelineLoading=$isTimelineLoading",
                    )
            } else {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "🟡 handleInitialTimelineBuild: Skipping timeline build - roomId ($roomId) != currentRoomId ($currentRoomId). Cache seeded only.",
                    )
            }

            // Persist initial paginated events to cache
            // No local persistence - using cache only

            smallestRowId = RoomTimelineCache.getOldestCachedEventRowId(roomId)
        }
    }
}
