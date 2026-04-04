package net.vrkknn.andromuks

import org.json.JSONObject

/**
 * Edit chains, merged bubble content, and [MessageVersionsCache] updates for [AppViewModel].
 *
 * Mutable state lives on the ViewModel ([AppViewModel.eventChainMap], [AppViewModel.editEventsMap]);
 * this class only orchestrates updates.
 */
internal class EditVersionCoordinator(
    private val vm: AppViewModel
) {

    fun isEditEvent(event: TimelineEvent): Boolean {
        if (event.relationType == "m.replace" && !event.relatesTo.isNullOrBlank()) return true
        return when {
            event.type == "m.room.message" ->
                event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" ->
                event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
            else -> false
        }
    }

    /** Target event id for an edit (m.replace); supports sync_complete top-level relates_to only. */
    fun editTargetEventId(event: TimelineEvent): String? {
        val fromContent = when {
            event.type == "m.room.message" ->
                event.content?.optJSONObject("m.relates_to")?.optString("event_id")
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" ->
                event.decrypted?.optJSONObject("m.relates_to")?.optString("event_id")
            else -> null
        }?.takeIf { it.isNotBlank() }
        if (fromContent != null) return fromContent
        if (event.relationType == "m.replace") return event.relatesTo?.takeIf { it.isNotBlank() }
        return null
    }

    fun mergeVersionsDistinct(
        existing: List<MessageVersion>,
        extra: MessageVersion? = null
    ): List<MessageVersion> {
        return (if (extra != null) existing + extra else existing)
            .groupBy { it.eventId }
            .map { (_, versions) -> versions.maxByOrNull { it.timestamp } ?: versions.first() }
            .sortedByDescending { it.timestamp }
    }

    /**
     * OPTIMIZED: Process events to build version cache (O(n) where n = number of events)
     */
    fun processVersionedMessages(events: List<TimelineEvent>) {
        for (event in events) {
            when {
                event.type == "m.room.redaction" ||
                    (event.type == "m.room.encrypted" && event.decryptedType == "m.room.redaction") -> {
                    val redactsEventId = when {
                        event.type == "m.room.encrypted" && event.decryptedType == "m.room.redaction" -> {
                            event.decrypted?.optString("redacts")?.takeIf { it.isNotBlank() }
                        }
                        else -> {
                            event.content?.optString("redacts")?.takeIf { it.isNotBlank() }
                        }
                    }

                    if (redactsEventId != null) {
                        val versioned = MessageVersionsCache.getAllVersions()[redactsEventId]
                        if (versioned != null) {
                            MessageVersionsCache.updateVersion(
                                redactsEventId,
                                versioned.copy(
                                    redactedBy = event.eventId,
                                    redactionEvent = event
                                )
                            )
                        } else {
                            val originalEvent = RoomTimelineCache.getCachedEvents(event.roomId)
                                ?.find { it.eventId == redactsEventId }

                            if (originalEvent != null) {
                                MessageVersionsCache.updateVersion(
                                    redactsEventId,
                                    VersionedMessage(
                                        originalEventId = redactsEventId,
                                        originalEvent = originalEvent,
                                        versions = emptyList(),
                                        redactedBy = event.eventId,
                                        redactionEvent = event
                                    )
                                )
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d(
                                        "Andromuks",
                                        "AppViewModel: Redaction event ${event.eventId} (type=${event.type}, decryptedType=${event.decryptedType}) received before original $redactsEventId, but found original in cache"
                                    )
                                }
                            } else {
                                MessageVersionsCache.updateVersion(
                                    redactsEventId,
                                    VersionedMessage(
                                        originalEventId = redactsEventId,
                                        originalEvent = event,
                                        versions = emptyList(),
                                        redactedBy = event.eventId,
                                        redactionEvent = event
                                    )
                                )
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d(
                                        "Andromuks",
                                        "AppViewModel: Redaction event ${event.eventId} (type=${event.type}, decryptedType=${event.decryptedType}) received before original $redactsEventId - created placeholder"
                                    )
                                }
                            }
                        }
                    }
                }

                isEditEvent(event) -> {
                    val originalEventId = editTargetEventId(event)

                    if (originalEventId != null) {
                        val versioned = MessageVersionsCache.getAllVersions()[originalEventId]
                        if (versioned != null) {
                            val newVersion = MessageVersion(
                                eventId = event.eventId,
                                event = event,
                                timestamp = event.timestamp,
                                isOriginal = false
                            )

                            val updatedVersions = mergeVersionsDistinct(versioned.versions, newVersion)

                            val limitedVersions =
                                if (updatedVersions.size > AppViewModel.MAX_MESSAGE_VERSIONS_PER_EVENT) {
                                    updatedVersions.take(AppViewModel.MAX_MESSAGE_VERSIONS_PER_EVENT)
                                } else {
                                    updatedVersions
                                }

                            val updatedVersioned = versioned.copy(
                                versions = limitedVersions
                            )
                            MessageVersionsCache.updateVersion(originalEventId, updatedVersioned)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Added edit ${event.eventId} to original $originalEventId (total versions: ${updatedVersions.size})"
                                )
                            }
                        } else {
                            MessageVersionsCache.updateVersion(
                                originalEventId,
                                VersionedMessage(
                                    originalEventId = originalEventId,
                                    originalEvent = event,
                                    versions = listOf(
                                        MessageVersion(
                                            eventId = event.eventId,
                                            event = event,
                                            timestamp = event.timestamp,
                                            isOriginal = false
                                        )
                                    )
                                )
                            )
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Edit ${event.eventId} received before original $originalEventId - created placeholder"
                                )
                            }
                        }
                    }
                }

                event.type == "m.room.message" || event.type == "m.room.encrypted" -> {
                    val existing = MessageVersionsCache.getAllVersions()[event.eventId]

                    if (existing != null) {
                        val originalVersion = MessageVersion(
                            eventId = event.eventId,
                            event = event,
                            timestamp = event.timestamp,
                            isOriginal = true
                        )

                        val updatedVersions = mergeVersionsDistinct(
                            existing.versions.filter { !it.isOriginal },
                            originalVersion
                        )

                        MessageVersionsCache.updateVersion(
                            event.eventId,
                            existing.copy(
                                originalEvent = event,
                                versions = updatedVersions
                            )
                        )
                    } else {
                        MessageVersionsCache.updateVersion(
                            event.eventId,
                            VersionedMessage(
                                originalEventId = event.eventId,
                                originalEvent = event,
                                versions = listOf(
                                    MessageVersion(
                                        eventId = event.eventId,
                                        event = event,
                                        timestamp = event.timestamp,
                                        isOriginal = true
                                    )
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    fun buildEditChainsFromEvents(timelineList: List<TimelineEvent>, clearExisting: Boolean = true) {
        if (clearExisting) {
            vm.eventChainMap.clear()
            vm.editEventsMap.clear()
        }

        for (event in timelineList) {
            val isEditEvt = isEditEvent(event)

            if (isEditEvt) {
                vm.editEventsMap[event.eventId] = event
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Added edit event ${event.eventId} to edit events map"
                    )
                }
            } else {
                val existingEntry = vm.eventChainMap[event.eventId]
                if (existingEntry == null || event.timestamp > existingEntry.originalTimestamp) {
                    vm.eventChainMap[event.eventId] = AppViewModel.EventChainEntry(
                        eventId = event.eventId,
                        ourBubble = event,
                        replacedBy = existingEntry?.replacedBy,
                        originalTimestamp = event.timestamp
                    )
                    if (BuildConfig.DEBUG && existingEntry != null) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Updated existing event ${event.eventId} in chain mapping (newer timestamp)"
                        )
                    } else if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Added regular event ${event.eventId} to chain mapping"
                        )
                    }
                }
            }
        }
    }

    fun mergeEditContent(originalEvent: TimelineEvent, editEvent: TimelineEvent): TimelineEvent {
        val mergedContent = JSONObject(originalEvent.content.toString())

        val newContent = when {
            editEvent.type == "m.room.encrypted" -> editEvent.decrypted?.optJSONObject("m.new_content")
            editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.new_content")
            else -> null
        }
        if (newContent != null) {
            val mergedDecrypted = JSONObject(newContent.toString())

            val finalContent = if (originalEvent.type == "m.room.message") {
                val updatedContent = JSONObject(originalEvent.content.toString())
                updatedContent.put("body", newContent.optString("body", ""))
                updatedContent.put("msgtype", newContent.optString("msgtype", "m.text"))
                updatedContent
            } else {
                mergedContent
            }

            return originalEvent.copy(
                content = finalContent,
                decrypted = mergedDecrypted,
                redactedBy = originalEvent.redactedBy
            )
        }
        return originalEvent
    }

    fun getFinalEventForBubble(entry: AppViewModel.EventChainEntry): TimelineEvent {
        val initialBubble = entry.ourBubble
        if (initialBubble == null) {
            android.util.Log.e(
                "Andromuks",
                "AppViewModel: getFinalEventForBubble called with null ourBubble for event ${entry.eventId}"
            )
            throw IllegalStateException("Entry ${entry.eventId} has null ourBubble")
        }

        var currentEvent: TimelineEvent = requireNotNull(initialBubble) { "ourBubble should be non-null after null check" }
        var currentEntry = entry
        val visitedEvents = mutableSetOf<String>()

        var chainDepth = 0
        while (currentEntry.replacedBy != null) {
            if (chainDepth >= 20) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "AppViewModel: ChainDepth >= 20, we have to break")
                }
                break
            }
            chainDepth++

            val editEventId = currentEntry.replacedBy!!

            if (visitedEvents.contains(editEventId)) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: Infinite loop detected! Edit event ${editEventId} already visited in this chain"
                )
                break
            }
            visitedEvents.add(editEventId)

            val editEventNullable = vm.editEventsMap[editEventId]
            if (editEventNullable == null) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: Edit event ${editEventId} not found in edit events map"
                )
                break
            }

            val editEvent = requireNotNull(editEventNullable) { "Edit event $editEventId should be non-null after null check" }

            currentEvent = mergeEditContent(currentEvent, editEvent)

            val nextEntry = vm.eventChainMap[editEventId]
            if (nextEntry == null) {
                break
            }

            if (nextEntry.eventId == currentEntry.eventId) {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: Edit event ${editEventId} points to itself, breaking chain"
                )
                break
            }

            currentEntry = nextEntry
        }

        return currentEvent
    }

    fun findSupersededEvents(newEvent: TimelineEvent, existingEvents: List<TimelineEvent>): List<String> {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: findSupersededEvents called for event ${newEvent.eventId}"
            )
        }
        val supersededEventIds = mutableListOf<String>()

        val relatesTo = when {
            newEvent.type == "m.room.message" -> newEvent.content?.optJSONObject("m.relates_to")
            newEvent.type == "m.room.encrypted" && newEvent.decryptedType == "m.room.message" ->
                newEvent.decrypted?.optJSONObject("m.relates_to")
            else -> null
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: relatesTo for event ${newEvent.eventId}: $relatesTo")
        }

        val relatesToEventId = relatesTo?.optString("event_id")
        val relType = relatesTo?.optString("rel_type")

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: relatesToEventId: $relatesToEventId, relType: $relType"
            )
        }

        if (relType == "m.replace" && relatesToEventId != null) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: This is an edit event targeting $relatesToEventId"
                )
            }
            val originalEvent = existingEvents.find { it.eventId == relatesToEventId }
            if (originalEvent != null) {
                supersededEventIds.add(originalEvent.eventId)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Edit event ${newEvent.eventId} supersedes original event ${originalEvent.eventId}"
                    )
                }
            } else {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: WARNING - Could not find original event $relatesToEventId in existing events"
                    )
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Available event IDs: ${existingEvents.map { it.eventId }}"
                    )
                }
            }
        } else {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: Not an edit event (relType: $relType, relatesToEventId: $relatesToEventId)"
                )
            }
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "AppViewModel: Returning superseded event IDs: $supersededEventIds"
            )
        }
        return supersededEventIds
    }

    fun findChainEndOptimized(startEventId: String, cache: MutableMap<String, AppViewModel.EventChainEntry?>): AppViewModel.EventChainEntry? {
        if (cache.containsKey(startEventId)) {
            return cache[startEventId]
        }

        var currentId = startEventId
        var currentEntry = vm.eventChainMap[currentId]

        while (currentEntry?.replacedBy != null) {
            currentId = currentEntry.replacedBy!!
            if (cache.containsKey(currentId)) {
                val cachedEnd = cache[currentId]
                cache[startEventId] = cachedEnd
                return cachedEnd
            }
            currentEntry = vm.eventChainMap[currentId]
        }

        cache[startEventId] = currentEntry
        return currentEntry
    }

    fun findChainEnd(startEventId: String): AppViewModel.EventChainEntry? {
        var currentId = startEventId
        var currentEntry = vm.eventChainMap[currentId]

        while (currentEntry?.replacedBy != null) {
            currentId = currentEntry.replacedBy!!
            currentEntry = vm.eventChainMap[currentId]
        }

        return currentEntry
    }

    fun processEditRelationships() {
        if (vm.editEventsMap.size > 100) {
            val limitedEditEvents = vm.editEventsMap
                .values
                .sortedByDescending { it.timestamp }
                .take(100)
                .associateBy { it.eventId }
            vm.editEventsMap.clear()
            vm.editEventsMap.putAll(limitedEditEvents)
        }

        val sortedEditEvents = vm.editEventsMap.values.sortedBy { it.timestamp }

        for (editEvent in sortedEditEvents) {
            val editEventId = editEvent.eventId

            val targetEventId = editTargetEventId(editEvent)
            if (targetEventId != null) {
                val targetEntry = vm.eventChainMap[targetEventId]
                if (targetEntry != null) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "processEditRelationships: edit ${editEventId} targets ${targetEventId} (current replacedBy=${targetEntry.replacedBy})"
                        )
                    }
                    if (targetEntry.replacedBy != null) {
                        val chainEnd = findChainEndOptimized(targetEntry.replacedBy!!, mutableMapOf())
                        if (chainEnd != null) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "processEditRelationships: extending chain end ${chainEnd.eventId} with ${editEventId}"
                                )
                            }
                            chainEnd.replacedBy = editEventId
                        } else {
                            android.util.Log.w(
                                "Andromuks",
                                "processEditRelationships: could not find chain end for ${targetEntry.replacedBy}; replacing with ${editEventId}"
                            )
                            targetEntry.replacedBy = editEventId
                        }
                    } else {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "processEditRelationships: first edit for ${targetEventId} is ${editEventId}"
                            )
                        }
                        targetEntry.replacedBy = editEventId
                    }
                } else {
                    android.util.Log.w(
                        "Andromuks",
                        "processEditRelationships: target entry missing for edit ${editEventId} (target=${targetEventId})"
                    )
                }
            } else {
                android.util.Log.w(
                    "Andromuks",
                    "processEditRelationships: edit ${editEventId} missing relates_to event_id"
                )
            }
        }
    }

    fun handleEditEventInChain(editEvent: TimelineEvent) {
        val processedEditEvent = if (editEvent.type == "m.room.encrypted" && editEvent.decrypted == null) {
            editEvent
        } else {
            editEvent
        }

        vm.editEventsMap[editEvent.eventId] = processedEditEvent

        val relatesTo = when {
            editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.relates_to")
            editEvent.type == "m.room.encrypted" && editEvent.decryptedType == "m.room.message" ->
                editEvent.decrypted?.optJSONObject("m.relates_to")
            else -> null
        }

        val targetEventId = relatesTo?.optString("event_id")
        if (targetEventId != null) {
            val targetEntry = vm.eventChainMap[targetEventId]
            if (targetEntry != null) {
                targetEntry.replacedBy = editEvent.eventId
            }
        } else {
            android.util.Log.w(
                "Andromuks",
                "AppViewModel: Could not find target event ID in edit event ${editEvent.eventId}"
            )
        }
    }

    fun addNewEventToChain(event: TimelineEvent) {
        // If sync_complete delivers a confirmed ($) event before send_complete evicts the pending
        // echo, both would coexist in eventChainMap with the same transactionId. Evict the echo
        // here so we never have a duplicate-key situation in the LazyColumn.
        val txId = event.transactionId
        if (txId != null && !event.eventId.startsWith("~")) {
            val pendingId = vm.pendingEchoMap.remove(txId)
            if (pendingId != null) {
                vm.eventChainMap.remove(pendingId)
                vm.markTimelineEntrancePlayed(event.eventId)
            }
        }

        val existingEntry = vm.eventChainMap[event.eventId]
        if (existingEntry != null) {
            val existingBubble = existingEntry.ourBubble
            if (existingBubble != null && event.timelineRowid > 0 && existingBubble.timelineRowid <= 0) {
                vm.eventChainMap[event.eventId] = existingEntry.copy(
                    ourBubble = existingBubble.copy(timelineRowid = event.timelineRowid)
                )
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Updated eventChainMap timeline_rowid for ${event.eventId} (was ${existingBubble.timelineRowid}, now ${event.timelineRowid})"
                    )
                }
            }
            return
        }

        vm.eventChainMap[event.eventId] = AppViewModel.EventChainEntry(
            eventId = event.eventId,
            ourBubble = event,
            replacedBy = null,
            originalTimestamp = event.timestamp
        )
    }

    fun processNewEditRelationships(newEditEvents: List<TimelineEvent>) {
        for (editEvent in newEditEvents) {
            val editEventId = editEvent.eventId

            val relatesTo = when {
                editEvent.type == "m.room.message" -> editEvent.content?.optJSONObject("m.relates_to")
                editEvent.type == "m.room.encrypted" && editEvent.decryptedType == "m.room.message" ->
                    editEvent.decrypted?.optJSONObject("m.relates_to")
                else -> null
            }

            val targetEventId = relatesTo?.optString("event_id")
            if (targetEventId != null) {
                val targetEntry = vm.eventChainMap[targetEventId]
                if (targetEntry != null) {
                    if (targetEntry.replacedBy != null) {
                        val chainEnd = findChainEnd(targetEntry.replacedBy!!)
                        if (chainEnd != null) {
                            chainEnd.replacedBy = editEventId
                        }
                    } else {
                        targetEntry.replacedBy = editEventId
                    }
                }
            }
        }
    }
}
