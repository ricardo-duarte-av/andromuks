package net.vrkknn.andromuks.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.SyncUpdateResult
import org.json.JSONObject

object SpaceRoomParser {

    /**
     * Matrix reply fallback bodies start with quoted parent lines (`> ...`); the actual reply
     * follows after a blank line. Strip the quote block so room list shows the reply text,
     * not the parent message. Mirrors [net.vrkknn.andromuks.utils.html] stripReplyFallback logic.
     *
     * `internal` rather than private because the home-screen room widget needs the identical
     * treatment for the same reason — see
     * [net.vrkknn.andromuks.widget.WidgetEventFormatter.format].
     */
    internal fun stripMatrixReplyQuote(body: String): String {
        if (body.isEmpty()) return body
        val lines = body.split('\n')
        if (lines.isEmpty() || !lines.first().startsWith(">")) return body
        var index = 0
        while (index < lines.size && lines[index].startsWith(">")) index++
        if (index < lines.size && lines[index].isBlank()) index++
        val stripped = lines.drop(index).joinToString("\n").trim()
        return if (stripped.isNotBlank()) stripped else body
    }

    /**
     * Read `meta.tombstone` — the backend's summary of a room's `m.room.tombstone` state event, so
     * clients can mark upgraded/replaced rooms without waiting for the timeline to load. Returns
     * null for live rooms. Both inner fields are optional: a tombstone may carry no reason and no
     * successor; its presence alone is the signal.
     */
    private fun parseTombstone(meta: JSONObject): net.vrkknn.andromuks.TombstoneInfo? {
        val tombstone = meta.optJSONObject("tombstone") ?: return null
        return net.vrkknn.andromuks.TombstoneInfo(
            body = tombstone.optString("body").takeIf { it.isNotBlank() },
            replacementRoomId = tombstone.optString("replacement_room").takeIf { it.isNotBlank() },
        )
    }

    private fun contentHasInReplyTo(content: org.json.JSONObject?): Boolean {
        val relates = content?.optJSONObject("m.relates_to") ?: return false
        if (relates.optString("rel_type") == "m.replace") return false // edit, not reply
        val inReplyTo = relates.optJSONObject("m.in_reply_to") ?: return false
        return inReplyTo.optString("event_id").isNotBlank()
    }

    /** Labels for non-text room list previews (aligned with parseRoomFromJson emoji style). */
    private fun previewFromMsgtype(msgtype: String, body: String?): String? = when (msgtype) {
        "m.image" -> "📷 Image"
        "m.video" -> "🎥 Video"
        "m.audio" -> "🎵 Audio"
        "m.file" -> "📎 File"
        "m.location" -> "📍 Location"
        "m.sticker" -> body?.takeIf { it.isNotBlank() }?.let { "🎨 $it" } ?: "🎨 Sticker"
        else -> null
    }

    /**
     * Extract message preview and sender from a single event. Returns (messagePreview, messageSender, latestEventId)
     * or null if the event is not a message or has no usable content. Used so we can resolve the preview from
     * either meta.preview_event_rowid or the event with max timestamp when array order is not chronological.
     */
    private fun extractPreviewFromEvent(event: JSONObject): Triple<String, String, String?>? {
        val eventType = event.optString("type")
        val sender = event.optString("sender")?.takeIf { it.isNotBlank() } ?: return null
        val eventId = event.optString("event_id")?.takeIf { it.isNotBlank() }
        when (eventType) {
            "m.room.message" -> {
                val content = event.optJSONObject("content") ?: return null
                val relatesTo = content.optJSONObject("m.relates_to")
                val isEdit = relatesTo?.optString("rel_type") == "m.replace"
                var body = if (isEdit) {
                    content.optJSONObject("m.new_content")?.optString("body")?.takeIf { it.isNotBlank() }
                } else {
                    content.optString("body")?.takeIf { it.isNotBlank() }
                }
                if (body != null && !isEdit && contentHasInReplyTo(content)) {
                    val stripped = stripMatrixReplyQuote(body).trim()
                    if (stripped.isNotBlank()) body = stripped
                }
                // A gallery's body is its caption, so it would otherwise preview as bare text.
                galleryPreviewLabel(if (isEdit) content.optJSONObject("m.new_content") else content)
                    ?.let { return Triple(it, sender, eventId) }
                if (body != null) return Triple(body, sender, eventId)
                if (!isEdit) {
                    val msgtype = content.optString("msgtype", "")
                    val label = previewFromMsgtype(msgtype, content.optString("body"))
                    if (label != null) return Triple(label, sender, eventId)
                }
            }

            "m.room.encrypted" -> {
                val decryptedType = event.optString("decrypted_type")
                val decrypted = event.optJSONObject("decrypted")
                if (decrypted == null) {
                    // Server didn't provide decrypted content. Return a generic label so the
                    // preview updates (rather than preserving stale text from a previous message).
                    // Skip reaction/redaction events — they must not replace the message preview.
                    return when {
                        decryptedType == "m.reaction" || decryptedType == "m.room.redaction" -> null

                        // Poll responses/ends must not replace the preview either — they are
                        // satellites of the poll bubble, not messages in their own right.
                        isPollResponseType(decryptedType) || isPollEndType(decryptedType) -> null

                        isPollStartType(decryptedType) -> Triple(pollPreviewLabel(null), sender, eventId)

                        decryptedType == "m.sticker" ->
                            Triple(previewFromMsgtype("m.sticker", null) ?: "Sticker", sender, eventId)

                        else -> Triple("Encrypted message", sender, eventId)
                    }
                }
                if (isPollResponseType(decryptedType) || isPollEndType(decryptedType)) return null
                if (isPollStartType(decryptedType)) {
                    return Triple(pollPreviewLabel(pollQuestionFromContent(decrypted)), sender, eventId)
                }
                if (decryptedType == "m.room.message" || decryptedType == "m.text") {
                    val relatesTo = decrypted.optJSONObject("m.relates_to")
                    val isEdit = relatesTo?.optString("rel_type") == "m.replace"
                    var body = if (isEdit) {
                        decrypted.optJSONObject("m.new_content")?.optString("body")?.takeIf { it.isNotBlank() }
                    } else {
                        decrypted.optString("body")?.takeIf { it.isNotBlank() }
                    }
                    if (body != null && !isEdit && contentHasInReplyTo(decrypted)) {
                        val stripped = stripMatrixReplyQuote(body).trim()
                        if (stripped.isNotBlank()) body = stripped
                    }
                    galleryPreviewLabel(if (isEdit) decrypted.optJSONObject("m.new_content") else decrypted)
                        ?.let { return Triple(it, sender, eventId) }
                    if (body != null) return Triple(body, sender, eventId)
                    if (!isEdit) {
                        val msgtype = decrypted.optString("msgtype", "")
                        val label = previewFromMsgtype(msgtype, decrypted.optString("body"))
                        if (label != null) return Triple(label, sender, eventId)
                    }
                } else if (decryptedType == "m.sticker") {
                    val label = previewFromMsgtype("m.sticker", decrypted.optString("body"))
                    if (label != null) return Triple(label, sender, eventId)
                }
            }

            "m.sticker" -> {
                val content = event.optJSONObject("content") ?: return null
                val label = previewFromMsgtype("m.sticker", content.optString("body")) ?: return null
                return Triple(label, sender, eventId)
            }

            in POLL_START_TYPES -> {
                val content = event.optJSONObject("content")
                return Triple(pollPreviewLabel(pollQuestionFromContent(content)), sender, eventId)
            }

            // Votes and poll closures never become the room's preview text.
            in POLL_RESPONSE_TYPES, in POLL_END_TYPES -> return null
        }
        return null
    }

    /**
     * Result of applying a room object's `account_data` delta: the favourite / low-priority
     * tag state, plus [hasTags] indicating whether an `m.tag` event was actually present.
     *
     * When [hasTags] is false the delta didn't touch tags, so callers must preserve the
     * room's existing favourite / low-priority flags rather than clearing them. The
     * side effect of the call is writing `fi.mau.gomuks.preferences` and `m.fully_read`
     * into [net.vrkknn.andromuks.RoomAccountDataCache].
     */
    private data class RoomAccountDataResult(val isFavourite: Boolean, val isLowPriority: Boolean, val hasTags: Boolean)

    /**
     * Applies a room object's `account_data` to [net.vrkknn.andromuks.RoomAccountDataCache]
     * and extracts tag state. Safe to call for room objects that have no `meta` — catchup
     * syncs (and, since the omit-fields change, any sync) may send a room object carrying
     * only `account_data` when just the room's account data changed. Such objects must not
     * be dropped, or per-room tags / fully-read markers / gomuks preferences that changed
     * while disconnected would be lost.
     */
    private fun applyRoomAccountData(roomId: String, accountData: JSONObject?): RoomAccountDataResult {
        if (accountData == null) {
            return RoomAccountDataResult(isFavourite = false, isLowPriority = false, hasTags = false)
        }
        var isFavourite = false
        var isLowPriority = false
        var hasTags = false
        val tagData = accountData.optJSONObject("m.tag")
        if (tagData != null) {
            hasTags = true
            val tags = tagData.optJSONObject("content")?.optJSONObject("tags")
            if (tags != null) {
                if (tags.has("m.favourite")) {
                    isFavourite = true
                }
                if (tags.has("m.lowpriority")) {
                    isLowPriority = true
                }
            }
        }
        val gomuksPrefData = accountData.optJSONObject("fi.mau.gomuks.preferences")
        if (gomuksPrefData != null) {
            net.vrkknn.andromuks.RoomAccountDataCache.setRoomAccountData(
                roomId,
                "fi.mau.gomuks.preferences",
                gomuksPrefData,
            )
        }
        // m.fully_read: the read-up-to marker. Cached so the timeline can draw an
        // "unread" divider at this position. May be advanced by another client and
        // arrive here via a later sync_complete.
        val fullyReadData = accountData.optJSONObject("m.fully_read")
        if (fullyReadData != null) {
            net.vrkknn.andromuks.RoomAccountDataCache.setRoomAccountData(roomId, "m.fully_read", fullyReadData)
        }
        // MSC4461 rev-3 allows per-message profiles (and a room default) in room account data, where
        // they outrank the global ones. Cached so the composer picker and chip agree with the
        // profile gomuks will actually attach when sending in this room.
        listOf("fi.mau.msc4461.per_message_profiles.v3", "m.per_message_profiles").forEach { type ->
            accountData.optJSONObject(type)?.let {
                net.vrkknn.andromuks.RoomAccountDataCache.setRoomAccountData(roomId, type, it)
            }
        }
        return RoomAccountDataResult(isFavourite, isLowPriority, hasTags)
    }

    /**
     * Parses incremental sync updates and returns what changed.
     * Handles room updates, new rooms, and removed rooms.
     * 
     * @param syncJson The sync_complete JSON object
     * @param memberCache Cache of room member profiles
     * @param appViewModel AppViewModel instance (for accessing existing rooms)
     * @param existingRooms Map of existing room IDs to RoomItems (for change detection)
     */
    suspend fun parseSyncUpdate(
        syncJson: JSONObject,
        memberCache: Map<String, Map<String, net.vrkknn.andromuks.MemberProfile>>? = null,
        appViewModel: net.vrkknn.andromuks.AppViewModel? = null,
        existingRooms: Map<String, net.vrkknn.andromuks.RoomItem>? = null,
        isClearState: Boolean = false,
    ): SyncUpdateResult {
        val data = syncJson.optJSONObject("data") ?: return SyncUpdateResult(emptyList(), emptyList(), emptyList())

        // Parse spaces from sync data
        val discoveredSpaceIds = mutableSetOf<String>()

        // CRITICAL: If clear_state=true, always clear spaces (even if top_level_spaces is null/empty)
        // The clear_state message has all keys null/empty, and subsequent messages will repopulate
        if (isClearState) {
            val currentSpacesSize = appViewModel?.allSpaces?.size ?: 0
            if (BuildConfig.DEBUG) {
                android.util.Log.w(
                    "Andromuks",
                    "SpaceRoomParser: clear_state=true - clearing $currentSpacesSize spaces (will be repopulated by subsequent sync_complete messages)",
                )
            }
            appViewModel?.updateAllSpaces(emptyList())
        }

        // Process top_level_spaces if present (in clear_state message or subsequent messages)
        val topLevelSpaces = data.optJSONArray("top_level_spaces")
        if (topLevelSpaces != null) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "SpaceRoomParser: top_level_spaces found with ${topLevelSpaces.length()} items (clear_state=$isClearState)",
                )
            }
            // Only parse basic space info, don't populate edges yet
            val spaces = parseSpacesBasic(data, appViewModel)
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "SpaceRoomParser: Parsed ${spaces.size} spaces from sync data (clear_state=$isClearState)",
                )
            }
            if (spaces.isNotEmpty() && BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "SpaceRoomParser: Space names: ${spaces.map { it.name }.joinToString(", ")}",
                )
            }

            // SAFETY FIX: Only update allSpaces if we have spaces (non-empty list)
            // This prevents clearing spaces when backend sends empty array in normal syncs
            if (spaces.isNotEmpty()) {
                val currentSpacesSize = appViewModel?.allSpaces?.size ?: 0
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "SpaceRoomParser: Calling updateAllSpaces with ${spaces.size} spaces (current: $currentSpacesSize, clear_state=$isClearState)",
                    )
                }
                appViewModel?.updateAllSpaces(spaces)
            } else {
                val currentSpacesSize = appViewModel?.allSpaces?.size ?: 0
                if (BuildConfig.DEBUG) {
                    android.util.Log.w(
                        "Andromuks",
                        "SpaceRoomParser: ⚠️ Received empty spaces array (clear_state=$isClearState, current spaces: $currentSpacesSize) - preserving existing spaces",
                    )
                }
            }
            discoveredSpaceIds.addAll(spaces.map { it.id })
        } // else: top_level_spaces is null - don't update, preserve existing spaces

        // CRITICAL FIX: Store space_edges even if top_level_spaces is null
        // This allows space edges to update existing spaces even when top_level_spaces isn't present
        // Space edges can arrive in separate sync_complete messages and should be processed
        val spaceEdges = data.optJSONObject("space_edges")
        if (spaceEdges != null) {
            val currentSpacesSize = appViewModel?.allSpaces?.size ?: 0
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Andromuks",
                    "SpaceRoomParser: Storing space_edges (current spaces: $currentSpacesSize, top_level_spaces was ${if (topLevelSpaces != null) "present" else "null"})",
                )
            }
            appViewModel?.storeSpaceEdges(spaceEdges)
            // Keys of space_edges are also space IDs (can include nested spaces)
            val edgeKeys = spaceEdges.keys()
            while (edgeKeys.hasNext()) {
                val id = edgeKeys.next()
                if (!id.isNullOrBlank()) {
                    discoveredSpaceIds.add(id)
                }
            }
        }

        // Debug: Log member cache contents
        // Log.d("Andromuks", "SpaceRoomParser: Member cache has ${memberCache?.size ?: 0} rooms")

        val updatedRooms = mutableListOf<RoomItem>()
        val newRooms = mutableListOf<RoomItem>()
        val removedRoomIds = mutableListOf<String>()
        // Rooms whose m.tag was present in this delta → their favourite/low-priority flags are
        // authoritative (see SyncUpdateResult.authoritativeTagRoomIds). Only ever written from the
        // sequential meta-less loop and the sequential result-collection loop below (never from the
        // parallel async parse bodies), so a plain set is safe.
        val authoritativeTagRoomIds = mutableSetOf<String>()

        // Process updated/new rooms
        val roomsJson = data.optJSONObject("rooms")
        if (roomsJson != null) {
            // PERFORMANCE: Extract all room data upfront to avoid repeated JSON operations.
            // A room object without `meta` is NOT dropped: catchup syncs (and any sync since the
            // omit-fields change) send account-data-only room objects with no meta. We apply their
            // account_data to the caches here, and — if the room is already known and the delta
            // touched tags — emit a tag-only RoomItem update. A meta-less object for an unknown
            // room can't be materialised into a RoomItem, so it stays cache-only (matching the
            // gomuks web frontend, which skips unknown meta-less rooms).
            val roomsToParse = mutableListOf<Triple<String, JSONObject, JSONObject>>()
            val roomKeys = roomsJson.keys()
            while (roomKeys.hasNext()) {
                val roomId = roomKeys.next()
                val roomObj = roomsJson.optJSONObject(roomId) ?: continue
                val meta = roomObj.optJSONObject("meta")
                if (meta != null) {
                    roomsToParse.add(Triple(roomId, roomObj, meta))
                    continue
                }
                val accountData = roomObj.optJSONObject("account_data") ?: continue
                val tagState = applyRoomAccountData(roomId, accountData)
                if (tagState.hasTags) {
                    // m.tag present → authoritative, even if the room is unknown (record it
                    // regardless so a tag-only delta for a not-yet-materialised room still marks
                    // the flags authoritative should the room appear later in the same batch).
                    authoritativeTagRoomIds.add(roomId)
                    existingRooms?.get(roomId)?.let { existing ->
                        updatedRooms.add(
                            existing.copy(
                                isFavourite = tagState.isFavourite,
                                isLowPriority = tagState.isLowPriority,
                            ),
                        )
                    }
                }
            }

            // PERFORMANCE: Parse rooms in parallel using coroutines
            // This significantly speeds up processing when there are many rooms (e.g., 100+)
            coroutineScope {
                val roomResults = roomsToParse.map { (roomId, roomObj, meta) ->
                    async(Dispatchers.Default) {
                        // Check if this is a space (skip spaces for now)
                        val type = meta.optJSONObject("creation_content")?.optString("type")?.takeIf { it.isNotBlank() }
                        if (type == "m.space") {
                            Triple(roomId, null as RoomItem?, false)
                        } else {
                            // Parse the room (always parse message previews). The Boolean is
                            // whether m.tag was present (→ authoritative favourite/low-priority).
                            val parsed = parseRoomFromJson(roomId, roomObj, meta, memberCache, appViewModel)
                            Triple(roomId, parsed?.first, parsed?.second ?: false)
                        }
                    }
                }

                // Collect results (maintains order)
                for ((roomId, room, hasTags) in roomResults.map { it.await() }) {
                    if (room == null) {
                        // This is a space
                        discoveredSpaceIds.add(roomId)
                        continue
                    }

                    if (hasTags) {
                        authoritativeTagRoomIds.add(roomId)
                    }

                    // Determine if this is a new room or updated room
                    val existingRoom = existingRooms?.get(roomId)
                    if (existingRoom == null) {
                        newRooms.add(room)
                    } else {
                        updatedRooms.add(room)
                    }
                }
            }
        }

        // Record any newly discovered space IDs so UI filtering can remove them from Home.
        if (discoveredSpaceIds.isNotEmpty()) {
            appViewModel?.registerSpaceIds(discoveredSpaceIds)
        }

        // Process left rooms
        val leftRooms = data.optJSONArray("left_rooms")
        if (leftRooms != null) {
            for (i in 0 until leftRooms.length()) {
                val roomId = leftRooms.optString(i)
                if (roomId.isNotBlank()) {
                    removedRoomIds.add(roomId)
                }
            }
        }

        // NOTE: invited_rooms are NOT handled here. Each entry is an InvitedRoom object
        // ({ room_id, invite_state, ... }), not a room-id string, and real invite ingestion is
        // SyncIngestor.processInvitedRooms → PendingInvitesCache. This applies equally to catchup
        // syncs, whose invited_rooms carry only invites changed since last_server_ts.

        // Debug: Log rooms with null messageSender (this is normal for receipt-only syncs)
        val roomsWithNullSender = updatedRooms.filter { it.messageSender == null }
        // if (roomsWithNullSender.isNotEmpty()) {
        //    Log.d("Andromuks", "SpaceRoomParser: ${roomsWithNullSender.size} rooms in sync with no new messages (may have receipts/state updates only)")
        //    roomsWithNullSender.forEach { room ->
        //        Log.d("Andromuks", "SpaceRoomParser: Room without new message - ID: ${room.id}, Name: ${room.name}")
        //    }
        // }

        // Log.d("Andromuks", "SpaceRoomParser: Sync update - updated: ${updatedRooms.size}, removed: ${removedRoomIds.size}")
        return SyncUpdateResult(
            updatedRooms,
            newRooms,
            removedRoomIds,
            authoritativeTagRoomIds = authoritativeTagRoomIds,
        )
    }

    /**
     * @return the parsed [RoomItem] paired with whether `account_data.m.tag` was present in this
     * room object. When the Boolean is true the room's favourite/low-priority flags are
     * authoritative and may be applied directly; when false the delta didn't touch tags and the
     * merge must preserve the existing flags. `null` on parse failure.
     */
    private fun parseRoomFromJson(
        roomId: String,
        roomObj: JSONObject,
        meta: JSONObject,
        memberCache: Map<String, Map<String, net.vrkknn.andromuks.MemberProfile>>? = null,
        appViewModel: net.vrkknn.andromuks.AppViewModel? = null,
    ): Pair<RoomItem, Boolean>? {
        try {
            // This is a regular room
            val name = meta.optString("name")?.takeIf { it.isNotBlank() } ?: roomId
            val avatar = meta.optString("avatar")?.takeIf { it.isNotBlank() }

            // Extract canonical alias from meta
            val canonicalAlias = meta.optString("canonical_alias")?.takeIf { it.isNotBlank() }

            // Extract unread count and highlight count from meta
            val unreadMessages = meta.optInt("unread_messages", 0)
            val unreadHighlights = meta.optInt("unread_highlights", 0)

            // Detect if this is a Direct Message room
            val isDirectMessage = detectDirectMessage(roomId, roomObj, meta, appViewModel)

            // Whether the backend holds this room's full member list. Recorded (not carried on
            // RoomItem — nothing renders it) so opening the room can top up a lazy-loaded one.
            // Guarded on has(): a meta object that omits the key says nothing, and recording a
            // false for it would provoke a pointless federated fetch. See RoomMemberListStatus.
            if (meta.has("has_member_list")) {
                net.vrkknn.andromuks.RoomMemberListStatus.setFromSync(
                    roomId,
                    meta.optBoolean("has_member_list", false),
                )
            }

            // Extract message preview and sender from events JSON
            // Always parse to keep summaries up-to-date (no local persistence, so only JSON parsing cost)
            var messagePreview: String? = null
            var messageSender: String? = null
            var latestEventId: String? = null

            val events = roomObj.optJSONArray("events")
            if (events != null && events.length() > 0) {
                // Backend may send events in any order; meta.preview_event_rowid designates the event to show.
                // If absent, use the message event with the latest timestamp (or rowid).
                val previewEventRowId = meta.optLong("preview_event_rowid", -1L).takeIf { it > 0 }
                var previewEvent: JSONObject? = null
                if (previewEventRowId != null) {
                    for (i in 0 until events.length()) {
                        val ev = events.optJSONObject(i) ?: continue
                        if (ev.optLong("rowid") == previewEventRowId) {
                            previewEvent = ev
                            break
                        }
                    }
                }
                if (previewEvent != null) {
                    extractPreviewFromEvent(previewEvent)?.let { (preview, snd, eid) ->
                        messagePreview = preview
                        messageSender = snd
                        latestEventId = eid
                    }
                }
                if (messagePreview == null) {
                    // No preview_event_rowid or event not a message: use message with max timestamp (then rowid)
                    var bestEvent: JSONObject? = null
                    var bestTs = -1L
                    var bestRowid = -1L
                    for (i in 0 until events.length()) {
                        val ev = events.optJSONObject(i) ?: continue
                        val t = ev.optString("type")
                        if (t != "m.room.message" && t != "m.room.encrypted" && t != "m.sticker") continue
                        val ts = ev.optLong("timestamp", 0L)
                        val rowid = ev.optLong("rowid", 0L)
                        if (ts > bestTs || (ts == bestTs && rowid > bestRowid)) {
                            bestTs = ts
                            bestRowid = rowid
                            bestEvent = ev
                        }
                    }
                    bestEvent?.let {
                        extractPreviewFromEvent(it)?.let { (preview, snd, eid) ->
                            messagePreview = preview
                            messageSender = snd
                            latestEventId = eid
                        }
                    }
                }
                // If still null (room has only reactions, joins, etc.), use the event with the
                // greatest timestamp so mark_read always has a valid target.
                if (latestEventId == null) {
                    var bestTs = -1L
                    for (i in 0 until events.length()) {
                        val ev = events.optJSONObject(i) ?: continue
                        val eid = ev.optString("event_id")?.takeIf { it.isNotBlank() } ?: continue
                        val ts = ev.optLong("timestamp", 0L)
                        if (ts > bestTs) {
                            bestTs = ts
                            latestEventId = eid
                        }
                    }
                }
            }

            // Extract sorting_timestamp from meta
            val sortingTimestamp = meta.optLong("sorting_timestamp", 0L).takeIf { it != 0L }

            // Extract tags from account_data.m.tag and populate RoomAccountDataCache.
            val roomAccountData = applyRoomAccountData(roomId, roomObj.optJSONObject("account_data"))
            val isFavourite = roomAccountData.isFavourite
            val isLowPriority = roomAccountData.isLowPriority

            // Resolve sender's display name. gomuks delivers per-room summary events
            // inline in roomObj.events: one m.room.member for the sender + one preview
            // message (both with timeline_rowid=-1). The m.room.member.content.displayname
            // is the authoritative value at sync time, so look there first. Fall back to
            // the cache (ProfileCache → RoomMemberCache → globals) only if no inline
            // member event is present (older rooms, missing sender, etc.).
            val senderDisplayName: String? = messageSender?.let { sender ->
                if (events != null) {
                    for (i in 0 until events.length()) {
                        val ev = events.optJSONObject(i) ?: continue
                        if (ev.optString("type") == "m.room.member" &&
                            ev.optString("state_key") == sender
                        ) {
                            val name = ev.optJSONObject("content")
                                ?.optString("displayname")
                                ?.takeIf { it.isNotBlank() }
                            if (name != null) return@let name
                        }
                    }
                }
                // Fallback for rooms whose member event wasn't included this sync
                appViewModel?.getUserProfile(sender, roomId)?.displayName?.takeIf { it.isNotBlank() }
            }

            return Pair(
                RoomItem(
                    id = roomId,
                    name = name,
                    messagePreview = messagePreview,
                    messageSender = messageSender,
                    unreadCount = if (unreadMessages > 0) unreadMessages else null,
                    highlightCount = if (unreadHighlights > 0) unreadHighlights else null,
                    avatarUrl = avatar,
                    sortingTimestamp = sortingTimestamp,
                    isDirectMessage = isDirectMessage,
                    isFavourite = isFavourite,
                    isLowPriority = isLowPriority,
                    tombstone = parseTombstone(meta),
                    canonicalAlias = canonicalAlias,
                    latestEventId = latestEventId,
                    senderDisplayName = senderDisplayName,
                ),
                roomAccountData.hasTags,
            )
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error parsing room $roomId", e)
            return null
        }
    }

    /**
     * Detects if a room is a Direct Message (DM) using multiple methods:
     * 1. Primary: dm_user_id field in meta (most reliable for gomuks JSON)
     * 2. Secondary: m.direct account data (more reliable than name-based detection)
     * 3. Fallback: room name patterns (contains @ symbol or looks like a user ID)
     */
    private fun detectDirectMessage(roomId: String, roomObj: JSONObject, meta: JSONObject, appViewModel: net.vrkknn.andromuks.AppViewModel? = null): Boolean {
        try {
            // Method 1: Check if dm_user_id is populated in meta - this indicates a DM
            val dmUserId = meta.optString("dm_user_id")?.takeIf { it.isNotBlank() }

            if (dmUserId != null) {
                // Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as DM (dm_user_id: $dmUserId)")
                return true
            }

            // Method 2: Check m.direct account data (secondary method)
            if (appViewModel != null && appViewModel.isDirectMessageFromAccountData(roomId)) {
                // Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as DM (m.direct account data)")
                return true
            }

            // Method 3: Fallback - Check if room name is exactly a Matrix user ID (not just contains @)
            val roomName = meta.optString("name", "")
            // The localpart and server name of an MXID never contain whitespace, so excluding it
            // keeps names that merely *begin* with a user ID from matching — e.g. a bot room called
            // "@janitor:example.com's Policy Change Notifications", which the old "[^:]+" pattern
            // happily swallowed (spaces and apostrophes are "not a colon") and mislabelled as a DM.
            val isExactMatrixUserId = roomName.matches(Regex("^@[^\\s:]+:[^\\s:/]+$"))

            if (isExactMatrixUserId) {
                // Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as DM (fallback: name is exact Matrix user ID: '$roomName')")
                return true
            }

            // Log.d("Andromuks", "SpaceRoomParser: Room $roomId detected as group room (no dm_user_id, not in m.direct, name: '$roomName')")
            return false
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error detecting DM status for room $roomId", e)
            return false
        }
    }

    /**
     * Parses basic space info from sync data (without edges).
     *
     * `meta` is omitted from a room object whenever the room's metadata did not change in this
     * delta, so a space listed in `top_level_spaces` frequently arrives with no `meta` at all — and
     * the result of this parse is handed straight to `updateAllSpaces`, which *replaces* the whole
     * list. Reading name/avatar from `meta` alone therefore renamed every unchanged space to its
     * raw `!id:server` and dropped its avatar. Fall back to what we already know, the same way
     * [updateExistingSpacesWithEdges] falls back to `joinedRoom` for child rooms.
     */
    private fun parseSpacesBasic(data: JSONObject, appViewModel: net.vrkknn.andromuks.AppViewModel? = null): List<net.vrkknn.andromuks.SpaceItem> {
        val spaces = mutableListOf<net.vrkknn.andromuks.SpaceItem>()

        try {
            // Get top_level_spaces array from sync data
            val topLevelSpaces = data.optJSONArray("top_level_spaces")
            if (topLevelSpaces != null) {
                // Log.d("Andromuks", "SpaceRoomParser: Found top_level_spaces with ${topLevelSpaces.length()} spaces")
                // Log.d("Andromuks", "SpaceRoomParser: top_level_spaces content: ${topLevelSpaces.toString()}")

                for (i in 0 until topLevelSpaces.length()) {
                    val spaceId = topLevelSpaces.optString(i)
                    if (spaceId.isNotBlank()) {
                        // Try to get space details from rooms data
                        val roomsJson = data.optJSONObject("rooms")
                        val spaceDetails = roomsJson?.optJSONObject(spaceId)
                        val meta = spaceDetails?.optJSONObject("meta")

                        // meta absent = "unchanged", not "empty". Keep the space we already have.
                        val known = appViewModel?.allSpaces?.find { it.id == spaceId }
                        val name = meta?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: known?.name?.takeIf { it != spaceId }
                            ?: appViewModel?.getRoomById(spaceId)?.name
                            ?: spaceId
                        val avatar = meta?.optString("avatar")?.takeIf { it.isNotBlank() }
                            ?: known?.avatarUrl
                            ?: appViewModel?.getRoomById(spaceId)?.avatarUrl

                        val spaceItem = net.vrkknn.andromuks.SpaceItem(
                            id = spaceId,
                            name = name,
                            avatarUrl = avatar,
                            // Edges are applied later by updateExistingSpacesWithEdges; preserve any
                            // children we already resolved so a meta-less sync doesn't blank the space.
                            rooms = known?.rooms ?: emptyList(),
                        )
                        spaces.add(spaceItem)
                        // Log.d("Andromuks", "SpaceRoomParser: Found space: $name (ID: $spaceId) - basic info only")
                    }
                }
            } // else {
            //    Log.d("Andromuks", "SpaceRoomParser: No top_level_spaces found in sync data")
            // }
        } catch (e: Exception) {
            Log.e("Andromuks", "SpaceRoomParser: Error parsing spaces", e)
        }

        // Log.d("Andromuks", "SpaceRoomParser: Parsed ${spaces.size} spaces (basic)")
        return spaces
    }

    /**
     * Updates existing spaces with child rooms from space_edges
     */
    fun updateExistingSpacesWithEdges(spaceEdges: JSONObject, data: JSONObject, appViewModel: net.vrkknn.andromuks.AppViewModel?) {
        try {
            val roomsJson = data.optJSONObject("rooms")
            val updatedSpaces = mutableListOf<net.vrkknn.andromuks.SpaceItem>()
            val discoveredSpaceIds = mutableSetOf<String>()
            val edgeKeys = spaceEdges.keys()
            while (edgeKeys.hasNext()) {
                val id = edgeKeys.next()
                if (!id.isNullOrBlank()) {
                    discoveredSpaceIds.add(id)
                }
            }

            // Get current spaces from AppViewModel
            val currentSpaces = appViewModel?.allSpaces ?: emptyList()
            // android.util.Log.d("Andromuks", "SpaceRoomParser: Updating ${currentSpaces.size} existing spaces with edges")

            // CRITICAL FIX: If currentSpaces is empty, don't update (would clear all spaces)
            // This can happen if updateExistingSpacesWithEdges is called before parseSpacesBasic
            // has populated spaces, or if spaces were cleared but not yet repopulated.
            // Wait for spaces to be populated first via parseSpacesBasic.
            if (currentSpaces.isEmpty()) {
                android.util.Log.w(
                    "Andromuks",
                    "SpaceRoomParser: updateExistingSpacesWithEdges called but currentSpaces is empty - skipping update to prevent clearing spaces",
                )
                return
            }

            for (space in currentSpaces) {
                val spaceEdgeArray = spaceEdges.optJSONArray(space.id)
                val childRooms = mutableListOf<net.vrkknn.andromuks.RoomItem>()

                if (spaceEdgeArray != null) {
                    // android.util.Log.d("Andromuks", "SpaceRoomParser: Space ${space.name} has ${spaceEdgeArray.length()} child rooms")
                    for (j in 0 until spaceEdgeArray.length()) {
                        val edge = spaceEdgeArray.optJSONObject(j)
                        val childId = edge?.optString("child_id")?.takeIf { it.isNotBlank() }
                        if (childId != null) {
                            // Skip nested spaces (show only rooms)
                            if (spaceEdges.has(childId)) continue
                            // Only show rooms the client is joined to. Use current sync data.rooms OR app's roomMap,
                            // since the first sync_complete may have space_edges while data.rooms is still empty.
                            val childRoomData = roomsJson?.optJSONObject(childId)
                            val joinedRoom = appViewModel?.getRoomById(childId)
                            val isJoined = childRoomData != null || joinedRoom != null
                            if (!isJoined) continue
                            val childMeta = childRoomData?.optJSONObject("meta")
                            val childName = childMeta?.optString("name")?.takeIf { it.isNotBlank() }
                                ?: joinedRoom?.name
                                ?: appViewModel?.allSpaces?.find { it.id == childId }?.name
                                ?: childId
                            val childAvatar = childMeta?.optString("avatar")?.takeIf { it.isNotBlank() }
                                ?: joinedRoom?.avatarUrl
                                ?: appViewModel?.allSpaces?.find { it.id == childId }?.avatarUrl
                            val unreadCount = childMeta?.optInt("unread_messages", 0) ?: joinedRoom?.unreadCount ?: 0
                            val highlightCount = childMeta?.optInt(
                                "unread_highlights",
                                0,
                            ) ?: joinedRoom?.highlightCount ?: 0
                            val childCanonicalAlias = childMeta?.optString(
                                "canonical_alias",
                            )?.takeIf { it.isNotBlank() }
                                ?: joinedRoom?.canonicalAlias
                            val childRoom = net.vrkknn.andromuks.RoomItem(
                                id = childId,
                                name = childName,
                                avatarUrl = childAvatar,
                                unreadCount = if (unreadCount > 0) unreadCount else null,
                                highlightCount = if (highlightCount > 0) highlightCount else null,
                                messagePreview = null,
                                messageSender = null,
                                isDirectMessage = false,
                                tombstone = childMeta?.let { parseTombstone(it) } ?: joinedRoom?.tombstone,
                                canonicalAlias = childCanonicalAlias,
                                latestEventId = null,
                            )
                            childRooms.add(childRoom)
                            // android.util.Log.d("Andromuks", "SpaceRoomParser: Added child room: $childName (unread: $unreadCount)")
                        }
                    }
                }

                // Create updated space with new child rooms
                val updatedSpace = space.copy(rooms = childRooms)
                updatedSpaces.add(updatedSpace)
                // android.util.Log.d("Andromuks", "SpaceRoomParser: Updated space ${space.name} with ${childRooms.size} rooms")
            }

            // Update the spaces in AppViewModel
            // CRITICAL FIX: Only update if we have spaces to update (should always be true after the check above)
            if (updatedSpaces.isNotEmpty()) {
                appViewModel?.updateAllSpaces(updatedSpaces)
            } else {
                android.util.Log.w(
                    "Andromuks",
                    "SpaceRoomParser: updateExistingSpacesWithEdges produced no updated spaces - skipping update",
                )
            }
            // android.util.Log.d("Andromuks", "SpaceRoomParser: Updated ${updatedSpaces.size} spaces with edges")
            if (discoveredSpaceIds.isNotEmpty()) {
                appViewModel?.registerSpaceIds(discoveredSpaceIds)
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "SpaceRoomParser: Error updating spaces with edges", e)
        }
    }
}
