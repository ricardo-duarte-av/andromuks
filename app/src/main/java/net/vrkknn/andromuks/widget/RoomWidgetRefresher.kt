package net.vrkknn.andromuks.widget

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.MemberProfile
import net.vrkknn.andromuks.ProfileCache
import net.vrkknn.andromuks.RoomMemberCache
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.TimelineEvent
import net.vrkknn.andromuks.processTimelineEvents
import net.vrkknn.andromuks.utils.ExecApi
import net.vrkknn.andromuks.utils.ExecBudget
import net.vrkknn.andromuks.utils.NotificationEnrichment
import net.vrkknn.andromuks.utils.POLL_START_TYPES
import net.vrkknn.andromuks.utils.RoomMetadataStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a [RoomWidgetSnapshot] for one room, from scratch, with no ViewModel.
 *
 * This is the widget's only route to authoritative data. It has to work in the worst case — app
 * force-stopped, WebSocket down, every in-memory cache empty — because that is the normal state of
 * a home screen. So it reads what survives process death (`RoomMetadataStore`, the media cache) and
 * fetches the rest over `/exec`, exactly as the notification path does.
 *
 * Blocking network work throughout; call from a worker, never the main thread.
 */
object RoomWidgetRefresher {
    private const val TAG = "RoomWidgetRefresher"

    /**
     * How many events to ask the server for. Much larger than the ~10 we render because the render
     * filter discards a lot — reactions, redactions, edits, membership noise, poll satellites — and
     * a chatty room can easily spend 40 events producing 10 visible lines.
     */
    private const val PAGINATE_LIMIT = 40

    /**
     * Event types the widget renders. The timeline whitelist (`RoomTimelineScreen`) minus
     * `m.reaction`: reactions never render as rows there either, they only decorate a bubble the
     * widget has no way to draw.
     */
    private val WIDGET_ALLOWED_TYPES: Set<String> = buildSet {
        add("m.room.message")
        add("m.room.encrypted")
        add("m.room.member")
        add("m.room.name")
        add("m.room.topic")
        add("m.room.avatar")
        add("m.room.tombstone")
        add("m.sticker")
        addAll(POLL_START_TYPES)
    }

    /**
     * Produce a fresh snapshot for [roomId] holding at most [limit] messages.
     *
     * Never throws: every failure mode degrades into a snapshot with an explanatory
     * [RoomWidgetSnapshot.State] and whatever content we already had, because a widget that goes
     * blank on a transient network error is worse than one showing slightly stale messages.
     */
    suspend fun refresh(context: Context, roomId: String, limit: Int, previous: RoomWidgetSnapshot? = null): RoomWidgetSnapshot = withContext(Dispatchers.IO) {
        val creds = ExecApi.readCredentials(context)
        if (!creds.isValid()) {
            Log.w(TAG, "No usable credentials; widget for $roomId cannot refresh")
            return@withContext (previous ?: RoomWidgetSnapshot(roomId, roomId)).copy(
                state = RoomWidgetSnapshot.State.SIGNED_OUT,
                stale = false,
                refreshing = false,
                updatedAt = System.currentTimeMillis(),
            )
        }

        try {
            build(context, roomId, limit, creds, previous)
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed for $roomId: ${e.message}", e)
            (previous ?: RoomWidgetSnapshot(roomId, roomId)).copy(
                state = RoomWidgetSnapshot.State.ERROR,
                refreshing = false,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun build(context: Context, roomId: String, limit: Int, creds: ExecApi.Credentials, previous: RoomWidgetSnapshot?): RoomWidgetSnapshot {
        val rawEvents = fetchEvents(context, roomId, creds)
        if (rawEvents.isEmpty()) {
            // An empty fetch is ambiguous — a genuinely empty room and a failed call look the same
            // from here — so keep whatever we were showing rather than blanking the widget.
            if (previous != null && previous.messages.isNotEmpty()) {
                return previous.copy(stale = false, refreshing = false, updatedAt = System.currentTimeMillis())
            }
            val identity = resolveRoomIdentity(context, roomId, previous)
            return RoomWidgetSnapshot(
                roomId = roomId,
                roomName = identity.name,
                messages = emptyList(),
                state = RoomWidgetSnapshot.State.OK,
                updatedAt = System.currentTimeMillis(),
            )
        }

        val edits = WidgetEventFormatter.collectEdits(rawEvents)
        val renderable = processTimelineEvents(
            timelineEvents = rawEvents,
            allowedEventTypes = WIDGET_ALLOWED_TYPES,
            showHiddenEvents = false,
            showMembershipEvents = false,
        ).takeLast(limit)

        val identity = resolveRoomIdentity(context, roomId, previous)
        val messages = toMessages(context, roomId, renderable, edits, creds)

        val roomAvatarPath = RoomWidgetAvatars.resolve(
            context = context,
            mxcUrl = identity.avatarMxc,
            fallbackName = identity.name,
            fallbackId = roomId,
            homeserverUrl = creds.homeserverUrl,
            authToken = creds.authToken,
        )

        // Keep only the avatar files this snapshot actually references; senders scroll out of the
        // window over time and their files would otherwise accumulate forever.
        RoomWidgetAvatars.prune(
            context,
            buildSet {
                messages.forEach { msg -> msg.senderAvatarPath?.let { add(it) } }
                roomAvatarPath?.let { add(it) }
            },
        )

        return RoomWidgetSnapshot(
            roomId = roomId,
            roomName = identity.name,
            roomAvatarPath = roomAvatarPath,
            messages = messages,
            updatedAt = System.currentTimeMillis(),
            state = RoomWidgetSnapshot.State.OK,
            stale = false,
            refreshing = false,
        )
    }

    /**
     * Fold newly arrived sync events into an existing [snapshot] without any network fetch.
     *
     * This is the cheap live-update path: the app is running, so sender profiles are already warm
     * in [ProfileCache], and the only possible I/O is downloading an avatar for a sender we have
     * never drawn before. Returns null when nothing in [newEvents] is renderable, so the caller can
     * skip a pointless redraw.
     *
     * Only additive changes are handled here. Edits, redactions and resets need machinery the
     * widget does not have ([net.vrkknn.andromuks.EditVersionCoordinator]), so the caller detects
     * those and forces a full [refresh] instead — see [RoomWidgetUpdater.onSyncEvents].
     */
    suspend fun appendEvents(context: Context, roomId: String, snapshot: RoomWidgetSnapshot, newEvents: List<TimelineEvent>, limit: Int): RoomWidgetSnapshot? =
        withContext(Dispatchers.IO) {
            val renderable = processTimelineEvents(
                timelineEvents = newEvents,
                allowedEventTypes = WIDGET_ALLOWED_TYPES,
                showHiddenEvents = false,
                showMembershipEvents = false,
            )
            val known = snapshot.messages.mapTo(mutableSetOf()) { it.eventId }
            val fresh = renderable.filter { it.eventId !in known }
            if (fresh.isEmpty()) return@withContext null

            val creds = ExecApi.readCredentials(context)
            val appended = toMessages(context, roomId, fresh, WidgetEventFormatter.collectEdits(newEvents), creds)
            if (appended.isEmpty()) return@withContext null

            snapshot.copy(
                messages = (snapshot.messages + appended).takeLast(limit),
                updatedAt = System.currentTimeMillis(),
                state = RoomWidgetSnapshot.State.OK,
                refreshing = false,
            )
        }

    /**
     * Convert renderable events into their display rows, resolving sender identity and avatars.
     * Shared by the full refresh and the incremental sync append so the two can never disagree
     * about how a message looks.
     */
    private suspend fun toMessages(
        context: Context,
        roomId: String,
        events: List<TimelineEvent>,
        edits: Map<String, JSONObject>,
        creds: ExecApi.Credentials,
    ): List<WidgetMessage> {
        if (events.isEmpty()) return emptyList()
        val profiles = resolveSenders(roomId, events.map { it.sender }.toSet(), creds)
        return events.map { event ->
            val profile = profiles[event.sender]
            val senderName = displayNameFor(profile, event.sender)
            WidgetMessage(
                eventId = event.eventId,
                senderId = event.sender,
                senderName = senderName,
                senderAvatarPath = RoomWidgetAvatars.resolve(
                    context = context,
                    mxcUrl = profile?.avatarUrl,
                    fallbackName = senderName,
                    fallbackId = event.sender,
                    homeserverUrl = creds.homeserverUrl,
                    authToken = creds.authToken,
                ),
                text = WidgetEventFormatter.format(event, edits),
                timestamp = event.timestamp,
            )
        }
    }

    /**
     * Timeline events for the room, preferring the in-process cache when it is already warm.
     *
     * The shortcut matters more than it looks: when the app is running and the user has the room
     * open, a widget refresh should cost nothing. When the process is cold — the normal case — the
     * cache is empty and we pay for one `/exec paginate`.
     */
    private fun fetchEvents(context: Context, roomId: String, creds: ExecApi.Credentials): List<TimelineEvent> {
        val cached = try {
            RoomTimelineCache.setAppContext(context.applicationContext)
            RoomTimelineCache.getCachedEventsForTimeline(roomId)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Timeline cache unavailable: ${e.message}")
            emptyList()
        }
        if (cached.size >= PAGINATE_LIMIT) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Using ${cached.size} warm cached events for $roomId")
            return cached
        }

        val body = JSONObject().apply {
            put("room_id", roomId)
            put("max_timeline_id", 0)
            put("limit", PAGINATE_LIMIT)
            put("reset", false)
        }
        // paginate answers either as {events: […]} or as a bare array; handleTimelineResponse
        // accepts both, so we must too.
        val result = ExecApi.execRaw(creds, "paginate", body)
        val eventsArray = when (val data = (result as? ExecApi.ExecResult.Success)?.data) {
            is JSONObject -> data.optJSONArray("events")

            is JSONArray -> data

            else -> {
                Log.w(TAG, "paginate failed for $roomId: $result")
                null
            }
        } ?: return cached

        val parsed = buildList {
            for (i in 0 until eventsArray.length()) {
                val json = eventsArray.optJSONObject(i) ?: continue
                try {
                    add(TimelineEvent.fromJson(json))
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Skipping unparseable event: ${e.message}")
                }
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Fetched ${parsed.size} events for $roomId via /exec")
        return parsed
    }

    /**
     * Display name + avatar for each sender about to be rendered.
     *
     * In-process caches first (`ProfileCache` then `RoomMemberCache`, the order documented in
     * docs/USER_PROFILES.md), then one batched `get_specific_room_state` for whoever is left.
     * Paginate responses carry no sender profiles, so on a cold start that request is the only way
     * to render a name instead of a localpart.
     */
    private fun resolveSenders(roomId: String, senderIds: Set<String>, creds: ExecApi.Credentials): Map<String, MemberProfile> {
        if (senderIds.isEmpty()) return emptyMap()
        val resolved = mutableMapOf<String, MemberProfile>()
        val missing = mutableListOf<String>()

        for (userId in senderIds) {
            val cached = try {
                ProfileCache.getFlattenedProfile(roomId, userId)
                    ?: RoomMemberCache.getMember(roomId, userId)
            } catch (e: Exception) {
                null
            }
            if (cached != null) resolved[userId] = cached else missing.add(userId)
        }
        if (missing.isEmpty()) return resolved

        val keys = JSONArray()
        missing.forEach { userId ->
            keys.put(
                JSONObject().apply {
                    put("room_id", roomId)
                    put("type", "m.room.member")
                    put("state_key", userId)
                },
            )
        }
        val response = ExecApi.callArray(creds, "get_specific_room_state", JSONObject().apply { put("keys", keys) })
        if (response == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Member lookup failed for ${missing.size} sender(s) in $roomId")
            return resolved
        }
        for (i in 0 until response.length()) {
            val event = response.optJSONObject(i)
            val userId = event?.optString("state_key")?.takeIf { it.isNotBlank() }
            val content = event?.optJSONObject("content")
            if (userId != null && content != null) {
                resolved[userId] = MemberProfile(
                    displayName = content.optString("displayname").takeIf { it.isNotBlank() && it != "null" },
                    avatarUrl = content.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }
        return resolved
    }

    /** Room header identity. [avatarMxc] is an `mxc://` URL — not yet a file path. */
    private data class RoomIdentity(val name: String, val avatarMxc: String?)

    /**
     * Resolve the room's header name and avatar.
     *
     * `RoomMetadataStore` is SQLite-backed and therefore the one source that survives process
     * death. `get_room_summary` covers a room the store has never seen (widget configured, app
     * reinstalled, cache cleared), and the previously displayed name is the last resort before
     * falling back to the raw room id.
     */
    private suspend fun resolveRoomIdentity(context: Context, roomId: String, previous: RoomWidgetSnapshot?): RoomIdentity {
        val row = try {
            RoomMetadataStore.initialize(context.applicationContext)
            RoomMetadataStore.getRow(roomId)
        } catch (e: Exception) {
            Log.w(TAG, "RoomMetadataStore unavailable for $roomId: ${e.message}")
            null
        }

        val name = row?.name?.takeIf { it.isNotBlank() && it != roomId }
            ?: NotificationEnrichment.fetchRoomName(context, roomId, ExecBudget.forNotification(maxCalls = 1))
            ?: previous?.roomName?.takeIf { it.isNotBlank() && it != roomId }
            ?: roomId

        return RoomIdentity(name = name, avatarMxc = row?.avatarMxc?.takeIf { it.isNotBlank() })
    }

    /** Presentational fallback: a blank display name renders as the user's localpart, not `@a:b`. */
    private fun displayNameFor(profile: MemberProfile?, userId: String): String = profile?.displayName?.takeIf { it.isNotBlank() }
        ?: userId.removePrefix("@").substringBefore(":")
}
