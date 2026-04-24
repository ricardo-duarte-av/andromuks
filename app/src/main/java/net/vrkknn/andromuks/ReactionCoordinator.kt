package net.vrkknn.andromuks

import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.extractReactionEventFromTimeline
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reaction orchestration for [AppViewModel]: maps, cache merge, WebSocket requests, and response
 * handling. UI helpers stay in [net.vrkknn.andromuks.utils.ReactionFunctions].
 */
internal class ReactionCoordinator(
    private val vm: AppViewModel
) {

    fun processReactionEvent(reactionEvent: ReactionEvent, isHistorical: Boolean = false) {
        val reactionKey = "${reactionEvent.sender}_${reactionEvent.emoji}_${reactionEvent.relatesToEventId}"

        if (!isHistorical && vm.processedReactions.contains(reactionKey)) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Skipping duplicate logical reaction: $reactionKey (eventId: ${reactionEvent.eventId})"
                )
            }
            return
        }

        if (!isHistorical) {
            vm.processedReactions.add(reactionKey)
        }

        if (!isHistorical && vm.processedReactions.size > 100) {
            val toRemove = vm.processedReactions.take(vm.processedReactions.size - 100)
            vm.processedReactions.removeAll(toRemove)
        }

        val previousReactions = vm.messageReactions[reactionEvent.relatesToEventId] ?: emptyList()

        if (isHistorical) {
            val existingForEmoji = previousReactions.find { it.emoji == reactionEvent.emoji }
            val alreadyHasUser =
                existingForEmoji?.userReactions?.any { it.userId == reactionEvent.sender } == true
            if (alreadyHasUser) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Skipping historical duplicate reaction (idempotent) for ${reactionEvent.sender} ${reactionEvent.emoji} on ${reactionEvent.relatesToEventId}"
                    )
                }
                return
            }
        }

        val updatedReactionsMap = net.vrkknn.andromuks.utils.processReactionEvent(
            reactionEvent,
            vm.currentRoomId,
            vm.messageReactions
        )
        vm.messageReactions = updatedReactionsMap
        val updatedReactions = updatedReactionsMap[reactionEvent.relatesToEventId] ?: emptyList()
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: processReactionEvent - eventId: ${reactionEvent.eventId}, logicalKey: $reactionKey, previous=${previousReactions.size}, updated=${updatedReactions.size}, reactionUpdateCounter: ${vm.reactionUpdateCounter}"
            )
        }

        if (!isHistorical) {
            val previousPairs = previousReactions.flatMap { reaction ->
                reaction.users.map { userId -> reaction.emoji to userId }
            }.toSet()
            val updatedPairs = updatedReactions.flatMap { reaction ->
                reaction.users.map { userId -> reaction.emoji to userId }
            }.toSet()

            val additionOccurred = updatedPairs.contains(reactionEvent.emoji to reactionEvent.sender) &&
                !previousPairs.contains(reactionEvent.emoji to reactionEvent.sender)
            val removalOccurred = previousPairs.contains(reactionEvent.emoji to reactionEvent.sender) &&
                !updatedPairs.contains(reactionEvent.emoji to reactionEvent.sender)

            if (BuildConfig.DEBUG && (additionOccurred || removalOccurred)) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Reaction change processed (in-memory only): addition=$additionOccurred, removal=$removalOccurred for event ${reactionEvent.relatesToEventId}"
                )
            }
        }

        vm.reactionUpdateCounter++
        vm.updateCounter++
    }

    fun populateMessageReactionsFromCache() {
        try {
            val cachedReactions = MessageReactionsCache.getAllReactions()
            if (cachedReactions.isNotEmpty()) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: populateMessageReactionsFromCache - populated with ${cachedReactions.size} events from cache"
                    )
                }
                vm.messageReactions = cachedReactions
                vm.reactionUpdateCounter++
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Failed to populate messageReactions from cache", e)
        }
    }

    fun loadReactionsForRoom(roomId: String, cachedEvents: List<TimelineEvent>, forceReload: Boolean = false) {
        if (cachedEvents.isEmpty()) return

        if (!forceReload && !vm.roomsWithLoadedReactions.add(roomId)) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Reactions for room $roomId already loaded from cache, skipping"
                )
            }
            return
        }

        val reactionEvents = RoomTimelineCache.getCachedReactionEvents(roomId)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: loadReactionsForRoom($roomId) - found ${reactionEvents.size} cached reaction events"
            )
        }
        if (reactionEvents.isNotEmpty()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Processing ${reactionEvents.size} reaction events from cache for room $roomId"
                )
            }
            var processedCount = 0
            for (reactionEvent in reactionEvents) {
                if (processReactionFromTimeline(reactionEvent)) {
                    processedCount++
                }
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Restored reactions from $processedCount/${reactionEvents.size} cached reaction events for room $roomId"
                )
            }
        } else {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: No cached reaction events found for room $roomId")
            }
        }
    }

    fun applyAggregatedReactionsFromEvents(events: List<TimelineEvent>, source: String) {
        if (events.isEmpty()) return

        val aggregatedByEvent = mutableMapOf<String, List<MessageReaction>>()
        for (event in events) {
            val reactionsObject = event.aggregatedReactions ?: continue

            try {
                val reactionList = mutableListOf<MessageReaction>()
                val keys = reactionsObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    try {
                        var count = 0
                        when (val value = reactionsObject.opt(key)) {
                            is Number -> count = value.toInt()
                            is JSONObject -> count = value.optInt("count", 0)
                            else -> count = reactionsObject.optInt(key, 0)
                        }
                        if (count > 0 && !key.isNullOrBlank()) {
                            reactionList.add(
                                MessageReaction(
                                    emoji = key,
                                    count = count,
                                    users = emptyList()
                                )
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Error processing reaction key '$key' for event ${event.eventId}: ${e.message}"
                        )
                    }
                }
                if (reactionList.isNotEmpty()) {
                    try {
                        aggregatedByEvent[event.eventId] = reactionList.sortedBy { it.emoji }
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "Andromuks",
                            "AppViewModel: Error sorting reactions for event ${event.eventId}, using unsorted: ${e.message}"
                        )
                        aggregatedByEvent[event.eventId] = reactionList
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: Error processing aggregated reactions for event ${event.eventId} from $source: ${e.message}",
                    e
                )
            }
        }

        if (aggregatedByEvent.isEmpty()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: applyAggregatedReactionsFromEvents($source) - no aggregated reactions found"
                )
            }
            return
        }

        val updated = vm.messageReactions.toMutableMap()
        var changed = false

        for ((eventId, reactions) in aggregatedByEvent) {
            val existing = updated[eventId]
            if (existing == null || existing.isEmpty()) {
                updated[eventId] = reactions
                changed = true
            } else {
                val aggregatedMap = reactions.associate { it.emoji to it.count }
                val mergedReactions = existing.map { existingReaction ->
                    val aggregatedCount = aggregatedMap[existingReaction.emoji] ?: existingReaction.count
                    if (aggregatedCount > existingReaction.count) {
                        existingReaction.copy(count = aggregatedCount)
                    } else {
                        existingReaction
                    }
                }.toMutableList()

                val existingEmojis = existing.map { it.emoji }.toSet()
                reactions.forEach { aggregatedReaction ->
                    if (aggregatedReaction.emoji !in existingEmojis) {
                        mergedReactions.add(aggregatedReaction)
                    }
                }

                updated[eventId] = mergedReactions
                changed = true
            }
        }

        if (changed) {
            vm.messageReactions = updated
            vm.reactionUpdateCounter++
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Applied aggregated reactions from $source for ${aggregatedByEvent.size} events"
                )
            }
        }
    }

    fun removeReaction(reactionEvent: ReactionEvent) {
        val reactionKey = "${reactionEvent.sender}_${reactionEvent.emoji}_${reactionEvent.relatesToEventId}"

        // Remove from dedup set so a future re-add (e.g. user reacts again) is not blocked.
        vm.processedReactions.remove(reactionKey)

        val currentReactions = vm.messageReactions.toMutableMap()
        val eventReactions = currentReactions[reactionEvent.relatesToEventId]?.toMutableList() ?: return

        val existingIndex = eventReactions.indexOfFirst { it.emoji == reactionEvent.emoji }
        if (existingIndex < 0) return

        val existing = eventReactions[existingIndex]
        val updatedUsers = existing.users.toMutableList()
        val updatedUserReactions = existing.userReactions.toMutableList()

        val userIndex = updatedUserReactions.indexOfFirst { it.userId == reactionEvent.sender }
        if (userIndex < 0) return

        updatedUsers.remove(reactionEvent.sender)
        updatedUserReactions.removeAt(userIndex)

        if (updatedUserReactions.isEmpty()) {
            eventReactions.removeAt(existingIndex)
        } else {
            eventReactions[existingIndex] = existing.copy(
                count = updatedUserReactions.size,
                users = updatedUsers,
                userReactions = updatedUserReactions
            )
        }

        currentReactions[reactionEvent.relatesToEventId] = eventReactions
        vm.messageReactions = currentReactions
        vm.reactionUpdateCounter++
        vm.updateCounter++

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: removeReaction - removed ${reactionEvent.emoji} from ${reactionEvent.sender} on ${reactionEvent.relatesToEventId}"
            )
        }
    }

    fun processReactionFromTimeline(event: TimelineEvent): Boolean {
        if (event.type != "m.reaction") return false

        val reaction = extractReactionEventFromTimeline(event) ?: return false

        return if (event.redactedBy == null) {
            processReactionEvent(reaction, isHistorical = true)
            true
        } else {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Skipping redacted historical reaction: ${reaction.emoji} from ${reaction.sender} to ${reaction.relatesToEventId}"
                )
            }
            false
        }
    }

    fun requestHistoricalReactions(roomId: String, smallestCached: Long) {
        if (vm.hasInitialPaginate(roomId)) {
            vm.logSkippedPaginate(roomId, "historical_reactions")
            return
        }
        val reactionRequestId = vm.getAndIncrementRequestId()
        vm.backgroundPrefetchRequests[reactionRequestId] = roomId
        val effectiveMaxTimelineId = if (smallestCached > 0) smallestCached else 0L
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: About to send reaction request - currentRoomId: ${vm.currentRoomId}, roomId=$roomId, smallestCached=$smallestCached, effectiveMaxTimelineId=$effectiveMaxTimelineId"
            )
        }
        val result = vm.sendWebSocketCommand(
            "paginate",
            reactionRequestId,
            mapOf(
                "room_id" to roomId,
                "max_timeline_id" to effectiveMaxTimelineId,
                "limit" to AppViewModel.INITIAL_ROOM_PAGINATE_LIMIT,
                "reset" to false
            )
        )
        if (result == WebSocketResult.SUCCESS) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: ✅ Sent reaction request for cached room: $roomId (requestId: $reactionRequestId)"
                )
            }
        } else {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Reaction request for $roomId (requestId: $reactionRequestId) could not be sent immediately (result=$result)"
            )
        }
    }

    fun requestReactionDetails(roomId: String, eventId: String) {
        if (!vm.isWebSocketConnected()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Skipping requestReactionDetails - WebSocket not connected (roomId=$roomId, eventId=$eventId)"
                )
            }
            return
        }

        val requestId = vm.getAndIncrementRequestId()
        vm.relatedEventsRequests[requestId] = roomId to eventId

        val commandData = mapOf(
            "room_id" to roomId,
            "event_id" to eventId,
            "relation_type" to "m.annotation"
        )

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: requestReactionDetails - sending get_related_events for roomId=$roomId, eventId=$eventId, requestId=$requestId"
            )
        }

        val result = vm.sendWebSocketCommand("get_related_events", requestId, commandData)
        if (result != WebSocketResult.SUCCESS) {
            vm.relatedEventsRequests.remove(requestId)
            if (BuildConfig.DEBUG) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: requestReactionDetails - get_related_events failed to send for roomId=$roomId, eventId=$eventId, requestId=$requestId, result=$result"
                )
            }
        }
    }

    fun sendReaction(roomId: String, eventId: String, emoji: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: sendReaction called with roomId: '$roomId', eventId: '$eventId', emoji: '$emoji'"
            )
        }

        if (WebSocketService.getWebSocket() == null) return

        val reactionRequestId = vm.getAndIncrementRequestId()

        vm.reactionRequests[reactionRequestId] = roomId

        val reactionKey = if (emoji.startsWith("![:") && emoji.contains("mxc://")) {
            val mxcStart = emoji.indexOf("mxc://")
            if (mxcStart >= 0) {
                val mxcEnd = emoji.indexOf("\"", mxcStart)
                if (mxcEnd > mxcStart) {
                    emoji.substring(mxcStart, mxcEnd).trim()
                } else {
                    val parenEnd = emoji.indexOf(")", mxcStart)
                    if (parenEnd > mxcStart) {
                        emoji.substring(mxcStart, parenEnd).trim()
                    } else {
                        emoji.substring(mxcStart).trim()
                    }
                }
            } else {
                emoji
            }
        } else {
            emoji
        }

        val commandData = mapOf(
            "room_id" to roomId,
            "type" to "m.reaction",
            "content" to mapOf(
                "m.relates_to" to mapOf(
                    "rel_type" to "m.annotation",
                    "event_id" to eventId,
                    "key" to reactionKey
                )
            ),
            "disable_encryption" to false,
            "synchronous" to false
        )

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: About to send WebSocket command: send_event with data: $commandData"
            )
        }
        vm.sendWebSocketCommand("send_event", reactionRequestId, commandData)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: WebSocket command sent with request_id: $reactionRequestId"
            )
        }

        val emojiForRecent = if (emoji.startsWith("![:") && emoji.contains("mxc://")) {
            val mxcStart = emoji.indexOf("mxc://")
            if (mxcStart >= 0) {
                val mxcEnd = emoji.indexOf("\"", mxcStart)
                if (mxcEnd > mxcStart) {
                    emoji.substring(mxcStart, mxcEnd)
                } else {
                    emoji.substring(mxcStart)
                }
            } else {
                emoji
            }
        } else {
            emoji
        }
        vm.updateRecentEmojis(emojiForRecent)
    }

    fun handleReactionResponse(requestId: Int, data: Any) {
        val roomId = vm.reactionRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Handling reaction response for room: $roomId, currentRoomId: ${vm.currentRoomId}"
            )
        }

        when (data) {
            is JSONObject -> {
                val event = TimelineEvent.fromJson(data)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Created reaction event: type=${event.type}, roomId=${event.roomId}, eventId=${event.eventId}"
                    )
                }
                if (event.type == "m.reaction") {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Ignoring reaction response event (temporary ID), waiting for send_complete: ${event.content?.optJSONObject("m.relates_to")?.optString("key")}"
                        )
                    }
                } else {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: Expected m.reaction event but got: ${event.type}"
                    )
                }
            }
            else -> {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Unhandled data type in handleReactionResponse: ${data::class.java.simpleName}"
                    )
                }
            }
        }
    }

    fun handleRelatedEventsResponse(requestId: Int, data: Any) {
        val (roomId, targetEventId) = vm.relatedEventsRequests.remove(requestId) ?: return
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Handling related_events response for room: $roomId, targetEventId=$targetEventId, dataType=${data::class.java.simpleName}"
            )
        }

        val eventsArray: JSONArray? = when (data) {
            is JSONArray -> data
            is JSONObject -> {
                data.optJSONArray("events") ?: JSONArray().apply { put(data) }
            }
            else -> null
        }

        if (eventsArray == null || eventsArray.length() == 0) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: related_events response empty for $targetEventId in $roomId"
                )
            }
            return
        }

        val timelineEvents = mutableListOf<TimelineEvent>()
        var reactionsProcessed = 0

        for (i in 0 until eventsArray.length()) {
            val eventJson = eventsArray.optJSONObject(i) ?: continue
            try {
                val event = TimelineEvent.fromJson(eventJson)
                timelineEvents.add(event)

                if (event.type == "m.reaction" && event.roomId == roomId) {
                    if (processReactionFromTimeline(event)) {
                        reactionsProcessed++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "Andromuks",
                    "AppViewModel: Error parsing related_event at index $i for $targetEventId in $roomId: ${e.message}",
                    e
                )
            }
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Processed $reactionsProcessed reaction events from related_events for target=$targetEventId in room=$roomId (total related events=${timelineEvents.size})"
            )
        }

        if (timelineEvents.isNotEmpty()) {
            applyAggregatedReactionsFromEvents(timelineEvents, source = "related_events")
        }
    }
}
