package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.database.SyncIngestor
import net.vrkknn.andromuks.utils.ReceiptFunctions
import net.vrkknn.andromuks.utils.SpaceRoomParser
import org.json.JSONObject

/**
 * Initial sync, [SyncUpdateResult] application, room/space state, and account_data from sync_complete.
 * Mutable UI state lives on [AppViewModel]; this class holds orchestration only.
 *
 * In [AppViewModel], the lazy [AppViewModel.syncRoomsCoordinator] is grouped with the other coordinators
 * and must be declared **before** [AppViewModel.syncBatchProcessor], which forwards sync_complete handling here.
 */
internal class SyncRoomsCoordinator(
    private val vm: AppViewModel
) {
    fun registerSpaceIds(spaceIds: Collection<String>) {
        with(vm) {
                    if (spaceIds.isEmpty()) return
                    val added = knownSpaceIds.addAll(spaceIds)
                    if (added) {
                        invalidateRoomSectionCache()
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Registered ${spaceIds.size} space IDs (total=${knownSpaceIds.size})")
                    }
        }
    }

    fun setSpaces(spaces: List<SpaceItem>, skipCounterUpdate: Boolean = false) {
        with(vm) {
                    if (spaces.isNotEmpty() && !initialSyncComplete) {
                        addStartupProgressMessage("Processing ${spaces.size} spaces...")
                    }
                    spaceList = spaces
        
                    // SYNC OPTIMIZATION: Allow skipping immediate counter updates for batched updates
                    if (!skipCounterUpdate) {
                        roomListUpdateCounter++
                        updateCounter++ // Keep for backward compatibility temporarily     
                    } 
        }
    }

    fun enterSpace(spaceId: String) {
        with(vm) {
                    currentSpaceId = spaceId
                    roomListUpdateCounter++
                    updateCounter++ // Keep for backward compatibility temporarily
        }
    }

    fun exitSpace() {
        with(vm) {
                    currentSpaceId = null
                    roomListUpdateCounter++
                    updateCounter++ // Keep for backward compatibility temporarily
        }
    }

    fun populateRoomMapFromCache() {
        with(vm) {
                    try {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRoomMapFromCache called - current roomMap size: ${roomMap.size}, cache size: ${RoomListCache.getRoomCount()}")
            
                        val cachedRooms = RoomListCache.getAllRooms()
                        if (cachedRooms.isNotEmpty()) {
                            // Populate roomMap with rooms from singleton cache
                            roomMap.putAll(cachedRooms)
                
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRoomMapFromCache - populated roomMap with ${cachedRooms.size} rooms from cache (new size: ${roomMap.size})")
                
                            // CRITICAL: If we loaded rooms from cache, mark spaces as loaded
                            // This prevents "Loading spaces..." from showing when we have rooms but spacesLoaded is false
                            // The cache only contains rooms that have been processed by SpaceRoomParser, so spaces are effectively loaded
                            if (!spacesLoaded && cachedRooms.isNotEmpty()) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateRoomMapFromCache - marking spaces as loaded since cache has ${cachedRooms.size} rooms")
                                spacesLoaded = true
                            }
                
                            // Update allRooms and invalidate cache
                            forceRoomListSort()
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: populateRoomMapFromCache - cache is empty, cannot populate roomMap")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Failed to populate roomMap from cache", e)
                    }
        }
    }

    fun populateSpacesFromCache() {
        with(vm) {
                    try {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache called - current allSpaces size: ${allSpaces.size}, cache size: ${SpaceListCache.getSpaceCount()}")
            
                        val cachedSpaces = SpaceListCache.getAllSpaces()
                        if (cachedSpaces.isNotEmpty()) {
                            // Populate allSpaces from singleton cache
                            allSpaces = cachedSpaces
                
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache - populated allSpaces with ${cachedSpaces.size} spaces from cache")
                
                            // Also restore space_edges if available
                            val cachedSpaceEdges = SpaceListCache.getSpaceEdges()
                            if (cachedSpaceEdges != null) {
                                storedSpaceEdges = cachedSpaceEdges
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache - restored space_edges from cache")
                            }
                
                            // Mark spaces as loaded since we restored them from cache
                            if (!spacesLoaded) {
                                spacesLoaded = true
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache - marking spaces as loaded")
                            }
                
                            roomListUpdateCounter++
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: populateSpacesFromCache - cache is empty, spaces will be loaded from sync_complete")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Failed to populate spaces from cache", e)
                    }
        }
    }

    fun processAccountData(accountDataJson: JSONObject) {
        with(vm) {
                    // CRITICAL FIX: Store account_data in singleton cache so all ViewModel instances can access it
                    // This ensures secondary instances (e.g., opened from Contacts) can access account_data
                    AccountDataCache.setAllAccountData(accountDataJson)
        
                    // Account data is already extracted, process it directly
                    if (BuildConfig.DEBUG) {
                        val allKeys = accountDataJson.keys().asSequence().toList()
                        android.util.Log.d("Andromuks", "AppViewModel: processAccountData - Processing account_data with ${allKeys.size} keys: ${allKeys.joinToString(", ")}")
                    }
        
                    // Process recent emoji account data
                    // Check if key is present (even if null/empty) - this indicates we should process it
                    if (accountDataJson.has("io.element.recent_emoji")) {
                        val recentEmojiData = accountDataJson.optJSONObject("io.element.recent_emoji")
                        if (recentEmojiData != null) {
                            val content = recentEmojiData.optJSONObject("content")
                            val recentEmojiArray = content?.optJSONArray("recent_emoji")
                
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("Andromuks", "AppViewModel: processAccountData - Found io.element.recent_emoji, content=${content != null}, array length=${recentEmojiArray?.length() ?: 0}")
                            }
                
                            if (recentEmojiArray != null && recentEmojiArray.length() > 0) {
                                val frequencies = mutableListOf<Pair<String, Int>>()
                                for (i in 0 until recentEmojiArray.length()) {
                                    val emojiEntry = recentEmojiArray.optJSONArray(i)
                                    if (emojiEntry != null && emojiEntry.length() >= 1) {
                                        val emoji = emojiEntry.optString(0)
                                        if (emoji.isNotBlank()) {
                                            // Get count from entry, default to 1 if not present
                                            val count = if (emojiEntry.length() >= 2) {
                                                emojiEntry.optInt(1, 1)
                                            } else {
                                                1
                                            }
                                            frequencies.add(Pair(emoji, count))
                                        }
                                    }
                                }
                                // Sort by frequency (descending) to ensure proper order
                                val sortedFrequencies = frequencies.sortedByDescending { it.second }
                                if (sortedFrequencies.isNotEmpty()) {
                                    // CRITICAL FIX: Always trust the server's data as the source of truth.
                                    // The server always sends the FULL list in account_data (not partial updates),
                                    // so we should always replace our local list with what the server sends.
                                    // This ensures we stay in sync with the server and other clients.
                                    recentEmojiFrequencies = sortedFrequencies.toMutableList()
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded ${sortedFrequencies.size} recent emojis from account_data (server is source of truth): ${sortedFrequencies.take(5).joinToString(", ") { "${it.first}(${it.second})" }}${if (sortedFrequencies.size > 5) "..." else ""}")
                                    val emojisList = recentEmojiFrequencies.map { it.first }
                                    RecentEmojisCache.set(emojisList)
                                    recentEmojis = emojisList
                                    // Mark that we've loaded the full list from the server
                                    hasLoadedRecentEmojisFromServer = true
                                } else {
                                    // Key is present but array is empty - clear recent emojis
                                    recentEmojiFrequencies.clear()
                                    RecentEmojisCache.clear()
                                    recentEmojis = emptyList()
                                    // Still mark as loaded (server has empty list, which is valid)
                                    hasLoadedRecentEmojisFromServer = true
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: io.element.recent_emoji is present but empty, cleared recent emojis")
                                }
                            } else {
                                // Key is present but content/array is null or empty - clear recent emojis
                                recentEmojiFrequencies.clear()
                                recentEmojis = emptyList()
                                // Still mark as loaded (server has empty/null list, which is valid)
                                hasLoadedRecentEmojisFromServer = true
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: io.element.recent_emoji is present but empty/null, cleared recent emojis")
                            }
                        } else {
                            // Key is present but value is null - clear recent emojis
                            recentEmojiFrequencies.clear()
                            recentEmojis = emptyList()
                            // Still mark as loaded (server has null value, which is valid)
                            hasLoadedRecentEmojisFromServer = true
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: io.element.recent_emoji is null, cleared recent emojis")
                        }
                    } else {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processAccountData - io.element.recent_emoji key not found in account_data")
                    }
                    // If key is missing, don't process it (preserve existing state)
        
                    // Process m.direct account data for DM room detection
                    if (accountDataJson.has("m.direct")) {
                        val mDirectData = accountDataJson.optJSONObject("m.direct")
                        if (mDirectData != null) {
                            val content = mDirectData.optJSONObject("content")
                            if (content != null && content.length() > 0) {
                                val dmRoomIds = mutableSetOf<String>()
                                val dmUserMap = mutableMapOf<String, MutableSet<String>>()
                    
                                // Extract all room IDs from m.direct content
                                val keys = content.names()
                                if (keys != null) {
                                    for (i in 0 until keys.length()) {
                                        val userId = keys.optString(i)
                                        val roomIdsArray = content.optJSONArray(userId)
                                        if (roomIdsArray != null) {
                                            val roomsForUser = dmUserMap.getOrPut(userId) { mutableSetOf() }
                                            for (j in 0 until roomIdsArray.length()) {
                                                val roomId = roomIdsArray.optString(j)
                                                if (roomId.isNotBlank()) {
                                                    dmRoomIds.add(roomId)
                                                    roomsForUser.add(roomId)
                                                }
                                            }
                                        }
                                    }
                                }
                    
                                // Update the DM room IDs cache
                                directMessageRoomIds = dmRoomIds
                                directMessageUserMap = dmUserMap.mapValues { it.value.toSet() }
                                if (BuildConfig.DEBUG) android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Loaded ${dmRoomIds.size} DM room IDs for ${dmUserMap.size} users from m.direct account data"
                                )
                    
                                // PERFORMANCE: Update existing rooms in roomMap with correct DM status from account_data
                                // This ensures rooms loaded from cache have correct isDirectMessage flag
                                updateRoomsDirectMessageStatus(dmRoomIds)
                            } else {
                                // Key is present but content is null or empty - clear DM room IDs
                                directMessageRoomIds = emptySet()
                                directMessageUserMap = emptyMap()
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: m.direct is present but empty/null, cleared DM room IDs")
                            }
                        } else {
                            // Key is present but value is null - clear DM room IDs
                            directMessageRoomIds = emptySet()
                                directMessageUserMap = emptyMap()
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: m.direct is null, cleared DM room IDs")
                        }
                    }
                    // If key is missing, don't process it (preserve existing state)
        
                    // Process m.ignored_user_list account data
                    if (accountDataJson.has("m.ignored_user_list")) {
                        val ignoredUserListData = accountDataJson.optJSONObject("m.ignored_user_list")
                        if (ignoredUserListData != null) {
                            val content = ignoredUserListData.optJSONObject("content")
                            if (content != null) {
                                val ignoredUsersObj = content.optJSONObject("ignored_users")
                                if (ignoredUsersObj != null) {
                                    val ignoredSet = mutableSetOf<String>()
                                    val keys = ignoredUsersObj.names()
                                    if (keys != null) {
                                        for (i in 0 until keys.length()) {
                                            val userId = keys.optString(i)
                                            if (userId.isNotBlank()) {
                                                ignoredSet.add(userId)
                                            }
                                        }
                                    }
                                    ignoredUsers = ignoredSet
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Loaded ${ignoredSet.size} ignored users from m.ignored_user_list")
                                } else {
                                    // Key is present but ignored_users is null or empty - clear ignored users
                                    ignoredUsers = emptySet()
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: m.ignored_user_list.ignored_users is null/empty, cleared ignored users")
                                }
                            } else {
                                // Key is present but content is null - clear ignored users
                                ignoredUsers = emptySet()
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: m.ignored_user_list.content is null, cleared ignored users")
                            }
                        } else {
                            // Key is present but value is null - clear ignored users
                            ignoredUsers = emptySet()
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: m.ignored_user_list is null, cleared ignored users")
                        }
                    }
                    // If key is missing, don't process it (preserve existing state)
        
                    // Process im.ponies.emote_rooms for custom emoji packs
                    if (accountDataJson.has("im.ponies.emote_rooms")) {
                        val emoteRoomsData = accountDataJson.optJSONObject("im.ponies.emote_rooms")
                        if (emoteRoomsData != null) {
                            val content = emoteRoomsData.optJSONObject("content")
                            val rooms = content?.optJSONObject("rooms")
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("Andromuks", "AppViewModel: processAccountData - Found im.ponies.emote_rooms, content=${content != null}, rooms=${rooms != null}, rooms length=${rooms?.length() ?: 0}")
                            }
                            if (rooms != null && rooms.length() > 0) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Found im.ponies.emote_rooms account data with ${rooms.length()} rooms")
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Found rooms object in emote_rooms, requesting emoji pack data")
                                // Request emoji pack data for each room/pack combination
                                val keys = rooms.names()
                                if (keys != null) {
                                    var packCount = 0
                                    for (i in 0 until keys.length()) {
                                        val roomId = keys.optString(i)
                                        val packsObj = rooms.optJSONObject(roomId)
                                        if (packsObj != null) {
                                            val packNames = packsObj.names()
                                            if (packNames != null) {
                                                for (j in 0 until packNames.length()) {
                                                    val packName = packNames.optString(j)
                                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting emoji pack data for pack $packName in room $roomId")
                                                    requestEmojiPackData(roomId, packName)
                                                    packCount++
                                                }
                                            }
                                        }
                                    }
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requested emoji pack data for $packCount packs across ${keys.length()} rooms")
                                } else {
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No room keys found in emote_rooms")
                                }
                            } else {
                                // Key is present but rooms is null or empty - clear emoji/sticker packs
                                EmojiPacksCache.clear()
                                StickerPacksCache.clear()
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: im.ponies.emote_rooms is present but empty/null, cleared emoji/sticker packs")
                            }
                        } else {
                            // Key is present but value is null - clear emoji/sticker packs
                            EmojiPacksCache.clear()
                            StickerPacksCache.clear()
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: im.ponies.emote_rooms is null, cleared emoji/sticker packs")
                        }
                    } else {
                        // Key is missing from incoming account_data - preserve existing state
                        // Only reload from storage if we have no packs loaded (e.g., after clear_state)
                        // Account data is fully populated on every websocket reconnect (clear_state: true)
                        // No need to reload from storage - if packs are missing, they'll come on next reconnect
                        if (customEmojiPacks.isEmpty() && stickerPacks.isEmpty()) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No im.ponies.emote_rooms in incoming sync and no packs loaded - will be populated on next reconnect")
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: No im.ponies.emote_rooms in incoming sync, preserving existing ${customEmojiPacks.size} emoji packs and ${stickerPacks.size} sticker packs")
                        }
                    }
        
                    // CRITICAL: Log completion of account data processing for debugging startup stalls
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "AppViewModel: Account data processed.")
                    }
                    addStartupProgressMessage("Account data processed.")
        }
    }

    fun updateRoomsDirectMessageStatus(dmRoomIds: Set<String>) {
        with(vm) {
                    var updatedCount = 0
        
                    // Update each room's isDirectMessage flag based on m.direct account data
                    // Update the map in place (roomMap is a val but points to a mutable map)
                    for ((roomId, room) in roomMap) {
                        val shouldBeDirect = dmRoomIds.contains(roomId)
                        if (room.isDirectMessage != shouldBeDirect) {
                            // Update room with correct DM status
                            roomMap[roomId] = room.copy(isDirectMessage = shouldBeDirect)
                            updatedCount++
                        }
                    }
        
                    if (updatedCount > 0) {
                        // Update allRooms to reflect the changes
                        allRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                        // Invalidate cache to force refresh of filtered sections
                        invalidateRoomSectionCache()
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updated $updatedCount rooms with correct DM status from m.direct/bridge data")
                    }
        }
    }

    suspend fun processSyncCompleteAtomic(
        syncJson: JSONObject,
        requestId: Int,
        runId: String,
        applyRoomListNow: Boolean = true,
        onComplete: (suspend () -> Unit)? = null
    ): SyncUpdateResult? {
        val context = vm.appContext
        if (context == null) {
            android.util.Log.w("Andromuks", "AppViewModel: Skipping sync processing because appContext is null")
            onComplete?.invoke()
            return null 
        }

        try {
            // clear_state must be handled BEFORE parsing/ingesting (atomic reset)
            val data = syncJson.optJSONObject("data")
            val isClearState = data?.optBoolean("clear_state") == true
            if (isClearState) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "🟣 processSyncCompleteAtomic: clear_state=true - clearing state (request_id=$requestId)")
                }
                handleClearStateReset()
            }

            // Update last sync timestamp immediately (lightweight) and notify service
            vm.lastSyncTimestamp = System.currentTimeMillis()
            WebSocketService.updateLastSyncTimestamp()

            vm.ensureSyncIngestor()
            val ingestor = vm.syncIngestor
            if (ingestor == null) {
                android.util.Log.w("Andromuks", "AppViewModel: syncIngestor is null - cannot ingest sync_complete")
                onComplete?.invoke()
                return null 
            }

            // Capture flags/ids once to keep consistent across background jobs.
            val visible = vm.isAppVisible
            val runIdForIngest = vm.currentRunId

            // JSONObject is not thread-safe: clone once and create isolated copies per dispatcher.
            val raw = try {
                syncJson.toString()
            } catch (e: Exception) {
                android.util.Log.e("Andromuks", "AppViewModel: Failed to stringify sync_complete JSON: ${e.message}", e)
                null
            } ?: run {
                onComplete?.invoke()
                return null 
            }

            // Line 453 — declare result holder before coroutineScope
            var syncResultForCaller: SyncUpdateResult? = null

            kotlinx.coroutines.coroutineScope {
                val ingestDeferred = async<SyncIngestor.IngestResult>(Dispatchers.IO) {
                    val jsonForIngest = JSONObject(raw)
                    ingestor.ingestSyncComplete(jsonForIngest, requestId, runIdForIngest, visible)
                }

                val parseDeferred = async<SyncUpdateResult>(Dispatchers.Default) {
                    val jsonForParse = JSONObject(raw)
                    val existingRoomsSnapshot = synchronized(vm.roomMap) { HashMap(vm.roomMap) }
                    SpaceRoomParser.parseSyncUpdate(
                        jsonForParse,
                        RoomMemberCache.getAllMembers(),
                        vm,
                        existingRooms = existingRoomsSnapshot,
                        isClearState = isClearState
                    )
                }

                val ingestResult = ingestDeferred.await()
                val syncResult = parseDeferred.await()

                // Apply UI state changes on main thread (Compose safety)
                withContext(Dispatchers.Main) {
                    try {
                        val jsonForMain = JSONObject(raw)
                        if (applyRoomListNow) {
                            processParsedSyncResult(syncResult, jsonForMain)
                        } else {
                            syncResultForCaller = syncResult                   // ← correct condition
                        }

                        // Apply invites (in-memory) and UI invalidation flags.
                        val invites = ingestResult?.invites ?: emptyList()
                        if (invites.isNotEmpty()) {
                            invites.forEach { invite ->
                                PendingInvitesCache.updateInvite(invite)
                            }
                            vm.needsRoomListUpdate = true
                            if (visible) {
                                vm.roomListUpdateCounter++
                            }
                        }

                        // Keep existing behavior: if cached-room events arrived, bump summary counter (foreground).
                        val roomsWithEvents = ingestResult?.roomsWithEvents ?: emptySet()
                        if (roomsWithEvents.isNotEmpty() && visible) {
                            vm.roomSummaryUpdateCounter++
                        }

                        SyncRepository.emitEvent(SyncEvent.RoomListSingletonReplicated(vm.viewModelId))
                        onComplete?.invoke()
                    } catch (e: Exception) {
                        android.util.Log.e("Andromuks", "AppViewModel: Crash applying sync_complete on main: ${e.message}", e)
                        onComplete?.invoke()
                    }
                }
            }

            // Update last_received_request_id AFTER we fully processed this message.
            if (requestId != 0) {
                WebSocketService.updateLastReceivedRequestId(requestId, context)
            }
            return syncResultForCaller
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: processSyncCompleteAtomic failed (request_id=$requestId): ${e.message}", e)
            onComplete?.invoke()
            return null
        }
    }

    suspend fun applyBatchedRoomListResult(result: SyncUpdateResult) {
        withContext(Dispatchers.Main) {
            processParsedSyncResult(result, JSONObject())
        }
    }

    fun handleClearStateReset() {
        with(vm) {
                    if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: clear_state=true received - clearing derived room/space state (events preserved)")
                    clearDerivedStateInMemory()
        
                    ensureSyncIngestor()
        
                    runCatching {
                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                            syncIngestor?.handleClearStateSignal()
                        }
                    }.onFailure {
                        if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "AppViewModel: Failed to clear derived state on clear_state: ${it.message}", it)
                    }
        }
    }

    fun clearDerivedStateInMemory() {
        with(vm) {
                    val previousSpacesSize = allSpaces.size
                    if (BuildConfig.DEBUG && previousSpacesSize > 0) {
                        android.util.Log.w("Andromuks", "AppViewModel: clearDerivedStateInMemory - clearing $previousSpacesSize spaces")
                    }
                    roomMap.clear()
                    allRooms = emptyList()
                    invalidateRoomSectionCache()
                    allSpaces = emptyList()
                    spaceList = emptyList()
                    knownSpaceIds.clear()
                    storedSpaceEdges = null
                    spacesLoaded = false
                    newlyJoinedRoomIds.clear()
                    loadedSections.clear()
        
                    synchronized(readReceiptsLock) {
                        readReceipts.clear()
                    }
                    roomsWithLoadedReceipts.clear()
                    roomsWithLoadedReactions.clear()
                    MessageReactionsCache.clear()
                    messageReactions = emptyMap()
        
                    // Also clear derived account_data caches so the next full sync repopulates from the
                    // authoritative dataset sent after clear_state=true.
                    recentEmojiFrequencies.clear()
                    recentEmojis = emptyList()
                    hasLoadedRecentEmojisFromServer = false
                    directMessageRoomIds = emptySet()
                    directMessageUserMap = emptyMap()
                    EmojiPacksCache.clear()
                    StickerPacksCache.clear()
        
                    // CRITICAL FIX: Clear singleton account_data cache on clear_state
                    AccountDataCache.clear()
        
                    // Clear pending invites - new invites will come from clear_state sync_complete
                    PendingInvitesCache.clear()
        
                    // CRITICAL: Clear singleton RoomListCache when clear_state=true is received
                    // This ensures that when WebSocket reconnects after primary AppViewModel dies,
                    // all AppViewModel instances (including new ones) start with a clean cache
                    RoomListCache.clear()
                    // CRITICAL: Also clear SpaceListCache when clear_state=true is received
                    // This ensures spaces are repopulated from the fresh sync_complete messages
                    SpaceListCache.clear()
                    ReadReceiptCache.clear()
                    MessageReactionsCache.clear()
                    RecentEmojisCache.clear()
                    PendingInvitesCache.clear()
                    MessageVersionsCache.clear()
                    RoomMemberCache.clear()
        
                    // CRITICAL: Clear timeline caches only when server sends clear_state=true (base set of data).
                    // Do not clear on init_complete or resume (last_received_event); only when clear_state is present.
                    RoomTimelineCache.clearAll()
                    oldestRowIdPerRoom.clear()
                    roomsWithPendingPaginate.clear()
        
                    // Force room list refresh to reflect cleared state until new data arrives
                    needsRoomListUpdate = true
                    scheduleUIUpdate("roomList")
        }
    }

    fun updateRoomsFromSyncJsonAsync(syncJson: JSONObject) {
        vm.viewModelScope.launch {
            updateRoomsFromSyncJsonAsyncBody(syncJson)
        }
    }

    /**
     * Suspend body used by [SyncRepository]'s single sync_complete pipeline and by [updateRoomsFromSyncJsonAsync].
     */
    suspend fun updateRoomsFromSyncJsonAsyncBody(syncJson: JSONObject) {
        vm.handleSyncToDeviceEvents(syncJson)

        // CRITICAL FIX: Queue sync_complete messages received before init_complete
        if (!vm.initialSyncPhase) {
            synchronized(vm.initialSyncCompleteQueue) {
                val clonedJson = JSONObject(syncJson.toString())
                val requestId = syncJson.optInt("request_id", 0)
                vm.initialSyncCompleteQueue.add(clonedJson)
                vm.pendingSyncCompleteCount = vm.initialSyncCompleteQueue.size
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Queued initial sync_complete message (request_id=$requestId, queue size: ${vm.initialSyncCompleteQueue.size}) - FIFO order preserved"
                    )
                }
            }
            return
        }

        val requestId = syncJson.optInt("request_id", 0)
        val runId = syncJson.optJSONObject("data")?.optString("run_id", "") ?: ""
        vm.syncBatchProcessor.processSyncComplete(syncJson, requestId, runId)
    }

    fun processParsedSyncResult(syncResult: SyncUpdateResult, syncJson: JSONObject) {
        with(vm) {
                    // CRITICAL: Increment sync message count FIRST to prevent duplicate processing
                    syncMessageCount++
        
                    // NOTE: Invites are loaded from sync_complete right after ingestSyncComplete completes
                    // (in the background thread, before processParsedSyncResult runs)
                    // This ensures invites are loaded before the UI checks for them
        
                    // CRITICAL FIX: Process read receipts from sync_complete for ALL rooms, not just the currently open one
                    // This ensures receipts are updated even when rooms are not currently open
                    // Receipts are stored globally (by eventId) but should be updated whenever sync_complete arrives
                    val data = syncJson.optJSONObject("data")
                    if (data != null) {
                        val rooms = data.optJSONObject("rooms")
                        if (rooms != null) {
                            val roomKeys = rooms.keys()
                            while (roomKeys.hasNext()) {
                                val roomId = roomKeys.next()
                                val roomData = rooms.optJSONObject(roomId) ?: continue

                                // Only process receipts for:
                                // 1) Rooms that are actively cached (have a timeline cache), OR
                                // 2) The room that is currently open in the UI.
                                // This avoids wasting work on rooms whose timeline we are not keeping,
                                // since paginate will provide authoritative receipts when they are opened.
                                val isActivelyCached = RoomTimelineCache.isRoomActivelyCached(roomId)
                                val isCurrentRoom = (currentRoomId == roomId)
                                if (!isActivelyCached && !isCurrentRoom) {
                                    continue
                                }

                                val receipts = roomData.optJSONObject("receipts")
                                if (receipts != null && receipts.length() > 0) {
                                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processParsedSyncResult - Processing read receipts from sync_complete for room: $roomId (${receipts.length()} event receipts)")
                                    synchronized(readReceiptsLock) {
                                        // Use processReadReceiptsFromSyncComplete - sync_complete moves receipts
                                        // CRITICAL FIX: Pass roomId to prevent cross-room receipt corruption
                                        ReceiptFunctions.processReadReceiptsFromSyncComplete(
                                            receipts,
                                            readReceipts,
                                            { readReceiptsUpdateCounter++ },
                                            { userId: String, previousEventId: String?, newEventId: String ->
                                                // Track receipt movement for animation (thread-safe)
                                                synchronized(readReceiptsLock) {
                                                    receiptMovements[userId] = Triple(previousEventId, newEventId, System.currentTimeMillis())
                                                }
                                                receiptAnimationTrigger = System.currentTimeMillis()
                                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Receipt movement detected: $userId from $previousEventId to $newEventId")
                                            },
                                            roomId = roomId // Pass room ID to prevent cross-room corruption
                                        )

                                        // Update singleton cache after processing receipts
                                        val receiptsForCache = readReceipts.mapValues { it.value.toList() }
                                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Updating ReadReceiptCache with ${receiptsForCache.size} events (${receiptsForCache.values.sumOf { it.size }} total receipts) from sync_complete for room: $roomId")
                                        ReadReceiptCache.setAll(receiptsForCache)
                                        if (BuildConfig.DEBUG) {
                                            val cacheAfter = ReadReceiptCache.getAllReceipts()
                                            android.util.Log.d("Andromuks", "AppViewModel: ReadReceiptCache after update: ${cacheAfter.size} events (${cacheAfter.values.sumOf { it.size }} total receipts)")
                                        }
                                    }
                                }
                            }
                        }
                    }
        
                    // Populate member cache from sync data and check for changes
                    val oldMemberStateHash = generateMemberStateHash()
                    populateMemberCacheFromSync(syncJson)
                    val newMemberStateHash = generateMemberStateHash()
                    val memberStateChanged = newMemberStateHash != oldMemberStateHash
                    val hasRoomChanges = syncResult.updatedRooms.isNotEmpty() ||
                            syncResult.newRooms.isNotEmpty() ||
                            syncResult.removedRoomIds.isNotEmpty()
                    val accountData = data?.optJSONObject("account_data")
                    // CRITICAL FIX: account_data is ALWAYS present in sync_complete (usually as {} for no updates)
                    // Empty {} means "no updates" - preserve existing state
                    // Non-empty means "update these keys" - process them
                    // null means "no account_data field" (shouldn't happen per protocol, but handle gracefully)
                    val accountDataChanged = accountData != null && accountData.length() > 0
        
                    // CRITICAL FIX: Process account_data BEFORE early return check
                    // This ensures account_data is ALWAYS processed when present with keys, even if there are no room/member changes
                    // This is essential after clear_state=true when account_data arrives in subsequent sync_completes
                    val isClearState = data?.optBoolean("clear_state") == true
                    if (accountData != null) {
                        if (accountData.length() > 0) {
                            // Account_data has keys - process them (this updates recent emojis, m.direct, etc.)
                            if (BuildConfig.DEBUG) {
                                val accountDataKeys = accountData.keys().asSequence().toList()
                                android.util.Log.d("Andromuks", "AppViewModel: processParsedSyncResult - Processing account_data with keys: ${accountDataKeys.joinToString(", ")} (clear_state=$isClearState)")
                            }
                            processAccountData(accountData)
                        } //else {
                        //    // Account_data is empty {} - no updates, preserve existing state
                        //    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processParsedSyncResult - Incoming account_data is empty {}, preserving existing state")
                        //}
                    } else {
                        // Account_data is null (special case: first clear_state message may have null)
                        // This means "no account_data updates" - preserve existing state
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: processParsedSyncResult - account_data is null (preserving existing state)")
                    }
        
                    // Early return check AFTER processing account_data
                    // This ensures account_data is processed even when there are no room/member changes
                    if (!hasRoomChanges && !accountDataChanged && !memberStateChanged) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: processParsedSyncResult - no changes detected (rooms/account/member), skipping UI work (account_data already processed above)"
                            )
                        }
                        return
                    }
        
                    // BATTERY OPTIMIZATION: Auto-save state periodically for crash recovery
                    // Foreground: Save every 10 syncs (user might close app)
                    // Background: Save every 50 syncs (less frequent to reduce I/O)
                    if (syncMessageCount > 0) {
                        val shouldSave = if (isAppVisible) {
                            syncMessageCount % 10 == 0
                        } else {
                            syncMessageCount % 50 == 0
                        }
                        if (shouldSave) {
                            appContext?.let { context ->
                                saveStateToStorage(context)
                            }
                        }
                    }
        
                    // BATTERY OPTIMIZATION: This loop only processes rooms that actually changed in this sync (not all 588 rooms)
                    // syncResult.updatedRooms typically contains 1-10 rooms per sync, not all rooms
                    // Total cost: ~0.01-0.1ms per sync (much better than processing all 588 rooms)
                    // Update existing rooms
                    syncResult.updatedRooms.forEach { room ->
                        val existingRoom = roomMap[room.id]
                        if (existingRoom != null) {
                            // Preserve existing message preview and sender if new room data doesn't have one
                            // CRITICAL: Also preserve isFavourite and isLowPriority flags if sync doesn't include account_data.m.tag
                            // This prevents favorite rooms from disappearing from the Favs tab
                            val updatedRoom = room.copy(
                                messagePreview = if (room.messagePreview.isNullOrBlank() && !existingRoom.messagePreview.isNullOrBlank()) {
                                    existingRoom.messagePreview
                                } else {
                                    room.messagePreview
                                },
                                messageSender = if (room.messageSender.isNullOrBlank() && !existingRoom.messageSender.isNullOrBlank()) {
                                    existingRoom.messageSender
                                } else {
                                    room.messageSender
                                },
                                // Preserve favorite and low priority flags if sync doesn't explicitly update them
                                // SpaceRoomParser only sets these to true if account_data.m.tag is present
                                // If sync doesn't include account_data, we preserve the existing values
                                isFavourite = room.isFavourite || existingRoom.isFavourite, // Keep true if either is true
                                isLowPriority = room.isLowPriority || existingRoom.isLowPriority, // Keep true if either is true
                                isDirectMessage = room.isDirectMessage || existingRoom.isDirectMessage, // Preserve DM status
                                // WRITE-ONLY BRIDGE INFO: Preserve bridge protocol avatar if it was previously set
                                // Bridge info comes from get_room_state (m.bridge event), not from sync_complete
                                // Once set, it's never removed (will be resolved on app restart if room is no longer bridged)
                                // CRITICAL OPTIMIZATION: Also check SharedPreferences cache for bridge info
                                bridgeProtocolAvatarUrl = run {
                                    val cachedBridgeAvatar = appContext?.let { context ->
                                        net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeAvatarUrl(context, room.id)
                                    }
                                    val cachedBridgeAvatarUrl = if (cachedBridgeAvatar != null && cachedBridgeAvatar.isNotEmpty()) {
                                        cachedBridgeAvatar
                                    } else {
                                        null
                                    }
                                    room.bridgeProtocolAvatarUrl 
                                        ?: existingRoom.bridgeProtocolAvatarUrl 
                                        ?: cachedBridgeAvatarUrl
                                }
                            )
                            // Log if favorite status was preserved (for debugging)
                            if (existingRoom.isFavourite && !room.isFavourite && updatedRoom.isFavourite) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Preserved isFavourite=true for room ${room.id} (sync didn't include account_data.m.tag)")
                            }
                            roomMap[room.id] = updatedRoom
                            // Update singleton cache
                            RoomListCache.updateRoom(updatedRoom)
                        } else {
                            // New room - check SharedPreferences cache for bridge info
                            val cachedBridgeAvatar = appContext?.let { context ->
                                net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeAvatarUrl(context, room.id)
                            }
                            val cachedBridgeAvatarUrl = if (cachedBridgeAvatar != null && cachedBridgeAvatar.isNotEmpty()) {
                                cachedBridgeAvatar
                            } else {
                                null
                            }
                
                            val roomWithBridgeInfo = if (cachedBridgeAvatarUrl != null) {
                                room.copy(bridgeProtocolAvatarUrl = cachedBridgeAvatarUrl)
                            } else {
                                room
                            }
                
                            roomMap[room.id] = roomWithBridgeInfo
                            // Update singleton cache
                            RoomListCache.updateRoom(roomWithBridgeInfo)
                            if (BuildConfig.DEBUG) {
                                if (cachedBridgeAvatarUrl != null) {
                                    android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name} (unread: ${room.unreadCount}) with cached bridge avatar")
                                } else {
                                    android.util.Log.d("Andromuks", "AppViewModel: Added new room: ${room.name} (unread: ${room.unreadCount})")
                                }
                            }
                        }
                    }
        
                    // BATTERY OPTIMIZATION: This loop only processes newly joined rooms (typically 0-1 per sync)
                    // Not all 588 rooms - only rooms that were just added
                    // Add new rooms
                    syncResult.newRooms.forEach { room ->
                        // Check SharedPreferences cache for bridge info
                        val cachedBridgeAvatar = appContext?.let { context ->
                            net.vrkknn.andromuks.utils.BridgeInfoCache.getBridgeAvatarUrl(context, room.id)
                        }
                        val cachedBridgeAvatarUrl = if (cachedBridgeAvatar != null && cachedBridgeAvatar.isNotEmpty()) {
                            cachedBridgeAvatar
                        } else {
                            null
                        }
            
                        val roomWithBridgeInfo = if (cachedBridgeAvatarUrl != null) {
                            room.copy(bridgeProtocolAvatarUrl = cachedBridgeAvatarUrl)
                        } else {
                            room
                        }
            
                        roomMap[room.id] = roomWithBridgeInfo
                        // Update singleton cache
                        RoomListCache.updateRoom(roomWithBridgeInfo)
            
                        // CRITICAL FIX: Only mark as "newly joined" if initial sync is complete
                        // During initial sync, all rooms are "new" because roomMap is empty, but they're not actually newly joined
                        // Only mark as newly joined for real-time updates after initial sync is complete
                        if (initialSyncProcessingComplete) {
                            // Initial sync is complete - this is a real new room, mark as newly joined
                        newlyJoinedRoomIds.add(room.id)
                        } else {
                            // Initial sync - just add the room without marking as newly joined
                        }
            
            
                    }
        
                    // CRITICAL: If we have newly joined rooms, force immediate sort to show them at the top
                    if (syncResult.newRooms.isNotEmpty()) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: New rooms detected - forcing immediate sort to show them at the top")
                        scheduleRoomReorder(forceImmediate = true)
                    }
        
                    // BATTERY OPTIMIZATION: This loop only processes rooms that were removed (typically 0 per sync)
                    // Not all 588 rooms - only rooms that were just left/removed
                    // Remove left rooms
                    var roomsWereRemoved = false
                    var invitesWereRemoved = false
                    val removedRoomIdsSet = syncResult.removedRoomIds.toSet()
                    syncResult.removedRoomIds.forEach { roomId ->
                        // CRITICAL: Check if left room is a pending invite first (user refused invite on another client)
                        val wasPendingInvite = PendingInvitesCache.getInvite(roomId) != null
                        if (wasPendingInvite) {
                            PendingInvitesCache.removeInvite(roomId)
                            invitesWereRemoved = true
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed refused invite (left_rooms): $roomId")
                        }
            
                        // Remove from roomMap if user was actually joined (user left room on another client)
                        val removedRoom = roomMap.remove(roomId)
                        // Remove from singleton cache
                        RoomListCache.removeRoom(roomId)
                        if (removedRoom != null) {
                            roomsWereRemoved = true
                            // Remove from newly joined set if it was there
                            newlyJoinedRoomIds.remove(roomId)
                
                
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed room (left_rooms): ${removedRoom.name}")
                        }
                    }
        
                    // CRITICAL: If rooms were removed, immediately filter them out from allRooms and update UI
                    if (roomsWereRemoved) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Rooms removed - immediately filtering from allRooms and updating UI")
                        // Immediately filter out removed rooms from allRooms
                        val filteredRooms = allRooms.filter { it.id !in removedRoomIdsSet }
                        allRooms = filteredRooms
                        invalidateRoomSectionCache()
            
                        // Also update spaces list
                        setSpaces(listOf(SpaceItem(id = "all", name = "All Rooms", avatarUrl = null, rooms = filteredRooms)), skipCounterUpdate = true)
            
                        // Trigger immediate UI update (bypass debounce)
                        needsRoomListUpdate = true
                        roomListUpdateCounter++
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Immediately updated UI after room removal (roomListUpdateCounter: $roomListUpdateCounter)")
                    }
        
                    // CRITICAL: If invites were removed (refused on another client), trigger UI update
                    if (invitesWereRemoved) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Invites removed (refused on another client) - updating UI")
                        needsRoomListUpdate = true
                        roomListUpdateCounter++
                    }
        
                    //if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Total rooms now: ${roomMap.size} (updated: ${syncResult.updatedRooms.size}, new: ${syncResult.newRooms.size}, removed: ${syncResult.removedRoomIds.size}) - sync message #$syncMessageCount [App visible: $isAppVisible]")
        
                    // DETECT INVITES ACCEPTED ON OTHER DEVICES: Remove pending invites for rooms already joined
                    val pendingInvitesMap = PendingInvitesCache.getAllInvites()
                    if (pendingInvitesMap.isNotEmpty()) {
                        val acceptedInvites = pendingInvitesMap.keys.filter { roomMap.containsKey(it) }
                        if (acceptedInvites.isNotEmpty()) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Detected ${acceptedInvites.size} invites already joined via sync - removing pending invites")
                
                            acceptedInvites.forEach { roomId ->
                                PendingInvitesCache.removeInvite(roomId)
                            }
                
                            // Invites are in-memory only - no local cleanup needed
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Removed ${acceptedInvites.size} invites from memory (accepted elsewhere)")
                
                            // Trigger UI update to remove invites from RoomListScreen
                            needsRoomListUpdate = true
                            roomListUpdateCounter++
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Room list updated after removing accepted invites (roomListUpdateCounter: $roomListUpdateCounter)")
                        }
                    }
        
                    // NOTE: Invites are loaded from sync_complete at the start of processParsedSyncResult()
                    // This ensures invites are always loaded even when there are no room changes
        
                    // BATTERY OPTIMIZATION: Disabled timeline event caching - events are always persisted to DB by SyncIngestor
                    // We now always load from DB when opening a room (no need for multi-room RAM cache)
                    // This saves ~6-26ms CPU per sync_complete and ~15MB RAM
                    // cacheTimelineEventsFromSync(syncJson)
        
                    // SYNC OPTIMIZATION: Update room data (last message, unread count) without immediate sorting
                    // This prevents visual jumping while still showing real-time updates
                    val allRoomsUnsorted = roomMap.values.toList()
        
                    // BATTERY OPTIMIZATION: Update low priority rooms set only when changed (saves SharedPreferences writes)
                    // This function now caches the last hash and only writes when low priority status actually changes
                    // Without this optimization, we'd write to SharedPreferences on every sync even if nothing changed
                    updateLowPriorityRooms(allRoomsUnsorted)
        
                    // Diff-based update: Only update UI if room state actually changed
                    // BATTERY OPTIMIZATION: generateRoomStateHash is lightweight (O(n) string operations) but necessary for change detection
                    // It allows us to skip expensive UI updates when room state hasn't changed
                    val newRoomStateHash = generateRoomStateHash(allRoomsUnsorted)
                    val roomStateChanged = newRoomStateHash != lastRoomStateHash
        
                    // BATTERY OPTIMIZATION: Skip expensive UI updates when app is in background
                    if (isAppVisible) {
                        // Trigger timestamp update on sync (only for visible UI)
                        triggerTimestampUpdate()
            
                        // SYNC OPTIMIZATION: Selective updates - only update what actually changed
                        if (roomStateChanged) {
                
                            // PERFORMANCE: Update room data in current order (preserves visual stability)
                            // If allRooms is empty or this is first sync, initialize with sorted list
                            if (allRooms.isEmpty()) {
                                // First sync - initialize with sorted list
                                val sortedRooms = roomMap.values.sortedByDescending { it.sortingTimestamp ?: 0L }
                                allRooms = sortedRooms
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Initializing allRooms with ${sortedRooms.size} sorted rooms")
                            } else {
                                // Update existing rooms in current order, add new rooms at end
                                // PERFORMANCE: Only create new RoomItem instances when data actually changes
                                val existingRoomIds = allRooms.map { it.id }.toSet()
                                var hasChanges = false
                                val updatedExistingRooms = allRooms.mapIndexed { index, existingRoom ->
                                    val updatedRoom = roomMap[existingRoom.id] ?: existingRoom
                                    // Only create new instance if data actually changed (data class equality check)
                                    if (updatedRoom != existingRoom) {
                                        hasChanges = true
                                        updatedRoom
                                    } else {
                                        existingRoom // Keep existing instance to avoid recomposition
                                    }
                                }
                    
                                // Add any new rooms that appeared in roomMap (at the end, will be sorted later)
                                val newRooms = roomMap.values.filter { it.id !in existingRoomIds }
                    
                                // Only update if there are actual changes (new rooms or updated rooms)
                                if (newRooms.isNotEmpty() || hasChanges) {
                                    // Combine existing (in current order) with new rooms (will be sorted on next reorder)
                                    allRooms = updatedExistingRooms + newRooms
                                    invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
                        
                                    // Mark for batched UI update (for badges/timestamps - no sorting)
                                    needsRoomListUpdate = true
                                    scheduleUIUpdate("roomList")
                                }
                            }
                
                            // PERFORMANCE: Use debounced room reordering (30 seconds) to prevent "room jumping"
                            // This allows real-time badge/timestamp updates while only re-sorting periodically
                            scheduleRoomReorder()
                
                            lastRoomStateHash = newRoomStateHash
                
                            // SHORTCUT OPTIMIZATION: Shortcuts only update when user sends messages (not on sync_complete)
                            // This drastically reduces shortcut updates. Removed shortcut updates from sync_complete processing.
                
                            // BATTERY OPTIMIZATION: Only update persons API if sync_complete has DM changes
                            // No need to sort - buildDirectPersonTargets() filters to DMs and doesn't use sorted order
                            vm.viewModelScope.launch(Dispatchers.Default) {
                                val syncRooms = (syncResult.updatedRooms + syncResult.newRooms).filter { 
                                    it.sortingTimestamp != null && it.sortingTimestamp > 0 
                                }
                    
                                val syncDMs = syncRooms.filter { it.isDirectMessage }
                                if (syncDMs.isNotEmpty()) {
                                    // Get all DMs from roomMap (no sorting needed - persons API doesn't care about order)
                                    val allDMs = roomMap.values.filter { it.isDirectMessage }
                                    personsApi?.updatePersons(buildDirectPersonTargets(allDMs))
                                }
                            }
                        } else {
                            // Room state hash unchanged - check if individual rooms need timestamp updates
                            // PERFORMANCE: Only update rooms that actually changed to avoid unnecessary recomposition
                            if (allRooms.isNotEmpty()) {
                                var needsUpdate = false
                                val updatedRooms = allRooms.map { existingRoom ->
                                    val updatedRoom = roomMap[existingRoom.id] ?: existingRoom
                                    // Only create new instance if data actually changed (data class equality check)
                                    if (updatedRoom != existingRoom) {
                                        needsUpdate = true
                                        updatedRoom
                                    } else {
                                        existingRoom // Keep existing instance to avoid recomposition
                                    }
                                }
                    
                                // Only update if something actually changed
                                if (needsUpdate) {
                                    allRooms = updatedRooms
                                    invalidateRoomSectionCache() // PERFORMANCE: Invalidate cached room sections
                        
                                    // Trigger UI update for timestamp changes only
                                    needsRoomListUpdate = true
                                    scheduleUIUpdate("roomList")
                                }
                            }
                        }
            
            
                        // SYNC OPTIMIZATION: Check if current room needs timeline update with diff-based detection
                        checkAndUpdateCurrentRoomTimelineOptimized(syncJson)
            
                        // Timeline is updated directly from sync_complete events via processSyncEventsArray()
                        // No DB persistence or refresh needed - all data is in-memory
            
                        // SYNC OPTIMIZATION: Schedule member update if member cache actually changed
                        if (memberStateChanged) {
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: SYNC OPTIMIZATION - Member state changed, scheduling UI update")
                            needsMemberUpdate = true
                            scheduleUIUpdate("member")
                            lastMemberStateHash = newMemberStateHash
                        }
                    } else {
                        // BATTERY OPTIMIZATION: App is in background - minimal processing for battery saving
                        // We skip expensive operations like sorting and UI updates since no one is viewing the app
                        //if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: BATTERY SAVE MODE - App in background, skipping UI updates")
            
                        // BATTERY OPTIMIZATION: Keep allRooms unsorted when backgrounded (skip expensive O(n log n) sort)
                        // We only need sorted rooms when updating shortcuts (every 10 syncs) or when app becomes visible
                        // This saves CPU time since sorting 588 rooms takes ~2-5ms per sync
                        allRooms = allRoomsUnsorted // Use unsorted list from roomMap - lightweight operation
            
                        // SHORTCUT OPTIMIZATION: Shortcuts only update when user sends messages (not on sync_complete)
                        // This drastically reduces shortcut updates. Removed shortcut updates from sync_complete processing.
            
                        // BATTERY OPTIMIZATION: Only update persons API if sync_complete has DM changes
                        // No need to sort - buildDirectPersonTargets() filters to DMs and doesn't use sorted order
                        vm.viewModelScope.launch(Dispatchers.Default) {
                            val syncRooms = (syncResult.updatedRooms + syncResult.newRooms).filter { 
                                it.sortingTimestamp != null && it.sortingTimestamp > 0 
                            }
                
                            val syncDMs = syncRooms.filter { it.isDirectMessage }
                            if (syncDMs.isNotEmpty()) {
                                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Background: Updating persons (DMs changed in sync_complete)")
                                // Get all DMs from roomMap (no sorting needed - persons API doesn't care about order)
                                val allDMs = roomMap.values.filter { it.isDirectMessage }
                                personsApi?.updatePersons(buildDirectPersonTargets(allDMs))
                            }
                        }
                        // Note: We don't invalidate cache on every sync when backgrounded - saves CPU time
                    }
        
                    // Set spacesLoaded after 3 sync messages, but don't trigger navigation yet
                    // Navigation will be triggered by onInitComplete() after all initialization is done
                    if (syncMessageCount >= 3 && !spacesLoaded) {
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Setting spacesLoaded after $syncMessageCount sync messages")
                        spacesLoaded = true
                    }
        
                    // Process space edges after roomMap is updated so spaces show only joined rooms.
                    // (Don't run from storeSpaceEdges — that runs before processParsedSyncResult, when data.rooms may be empty.)
                    if (storedSpaceEdges != null && initializationComplete) {
                        populateSpaceEdges()
                    }
        }
    }
}
