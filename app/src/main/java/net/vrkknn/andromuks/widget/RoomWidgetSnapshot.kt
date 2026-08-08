package net.vrkknn.andromuks.widget

import org.json.JSONArray
import org.json.JSONObject

/**
 * The complete, self-contained description of what one room widget instance should paint.
 *
 * The widget renders from **this and nothing else** — never from [net.vrkknn.andromuks.AppViewModel],
 * never from a live cache, never from the network. That is the whole point: `RoomTimelineCache` is
 * in-memory only and `AppViewModel.persistRenderableEvents` is a no-op, so after process death there
 * is no timeline to read. Every trigger (manual refresh, notification, sync) writes a snapshot and
 * asks Glance to redraw; render itself is pure and instant.
 *
 * Avatars are referenced by **file path**, never inlined as bytes: a snapshot is persisted to
 * SharedPreferences, and base64 bitmaps would blow both the prefs file and the RemoteViews IPC
 * budget. See [RoomWidgetStore] and docs/WIDGET.md.
 */
data class RoomWidgetSnapshot(
    val roomId: String,
    val roomName: String,
    val roomAvatarPath: String? = null,
    val messages: List<WidgetMessage> = emptyList(),
    val updatedAt: Long = 0L,
    val state: State = State.OK,
    /** Set when sync delivered an edit/redaction/reset we cannot resolve locally; forces a full refresh. */
    val stale: Boolean = false,
    /** True while a refresh is in flight, so the widget can show a spinner without a second write. */
    val refreshing: Boolean = false,
) {
    enum class State {
        /** Snapshot holds real content (possibly zero messages, if the room is genuinely empty). */
        OK,

        /** Never refreshed yet — freshly configured widget. */
        LOADING,

        /** No usable credentials in prefs; the widget offers a tap-to-sign-in target. */
        SIGNED_OUT,

        /** Refresh failed (network/HTTP/command error); previous messages, if any, are still shown. */
        ERROR,
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_VERSION, SCHEMA_VERSION)
        put(KEY_ROOM_ID, roomId)
        put(KEY_ROOM_NAME, roomName)
        roomAvatarPath?.let { put(KEY_ROOM_AVATAR, it) }
        put(KEY_UPDATED_AT, updatedAt)
        put(KEY_STATE, state.name)
        put(KEY_STALE, stale)
        put(KEY_REFRESHING, refreshing)
        put(KEY_MESSAGES, JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        /** Bump when the JSON shape changes incompatibly; older snapshots are then discarded, not migrated. */
        const val SCHEMA_VERSION = 1

        private const val KEY_VERSION = "v"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_ROOM_NAME = "room_name"
        private const val KEY_ROOM_AVATAR = "room_avatar"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_STATE = "state"
        private const val KEY_STALE = "stale"
        private const val KEY_REFRESHING = "refreshing"
        private const val KEY_MESSAGES = "messages"

        /**
         * Parse a snapshot previously written by [toJson]. Returns null for anything unusable —
         * malformed JSON, a schema version we don't understand, or a missing room id. A null here
         * simply means "no snapshot", which the widget renders as [State.LOADING] and then refreshes.
         */
        fun fromJson(json: JSONObject?): RoomWidgetSnapshot? {
            if (json == null) return null
            if (json.optInt(KEY_VERSION, -1) != SCHEMA_VERSION) return null
            val roomId = json.optString(KEY_ROOM_ID).takeIf { it.isNotBlank() } ?: return null
            val messagesArray = json.optJSONArray(KEY_MESSAGES)
            val messages = buildList {
                for (i in 0 until (messagesArray?.length() ?: 0)) {
                    messagesArray?.optJSONObject(i)?.let { WidgetMessage.fromJson(it) }?.let { add(it) }
                }
            }
            val state = try {
                State.valueOf(json.optString(KEY_STATE, State.OK.name))
            } catch (_: IllegalArgumentException) {
                State.OK
            }
            return RoomWidgetSnapshot(
                roomId = roomId,
                roomName = json.optString(KEY_ROOM_NAME),
                roomAvatarPath = json.optString(KEY_ROOM_AVATAR).takeIf { it.isNotBlank() },
                messages = messages,
                updatedAt = json.optLong(KEY_UPDATED_AT, 0L),
                state = state,
                stale = json.optBoolean(KEY_STALE, false),
                refreshing = json.optBoolean(KEY_REFRESHING, false),
            )
        }

        /** The snapshot a freshly configured widget shows until its first refresh lands. */
        fun loading(roomId: String, roomName: String): RoomWidgetSnapshot = RoomWidgetSnapshot(
            roomId = roomId,
            roomName = roomName,
            state = State.LOADING,
        )
    }
}

/**
 * One rendered timeline row. Already formatted — [text] is the final display string produced by
 * [WidgetEventFormatter], including the emoji prefix for media ("📷 Sent a photo"). The widget does
 * no event interpretation at paint time.
 */
data class WidgetMessage(
    val eventId: String,
    val senderId: String,
    val senderName: String,
    val senderAvatarPath: String? = null,
    val text: String,
    val timestamp: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_EVENT_ID, eventId)
        put(KEY_SENDER_ID, senderId)
        put(KEY_SENDER_NAME, senderName)
        senderAvatarPath?.let { put(KEY_SENDER_AVATAR, it) }
        put(KEY_TEXT, text)
        put(KEY_TIMESTAMP, timestamp)
    }

    companion object {
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_SENDER_ID = "sender_id"
        private const val KEY_SENDER_NAME = "sender_name"
        private const val KEY_SENDER_AVATAR = "sender_avatar"
        private const val KEY_TEXT = "text"
        private const val KEY_TIMESTAMP = "ts"

        fun fromJson(json: JSONObject): WidgetMessage? {
            val eventId = json.optString(KEY_EVENT_ID).takeIf { it.isNotBlank() } ?: return null
            return WidgetMessage(
                eventId = eventId,
                senderId = json.optString(KEY_SENDER_ID),
                senderName = json.optString(KEY_SENDER_NAME),
                senderAvatarPath = json.optString(KEY_SENDER_AVATAR).takeIf { it.isNotBlank() },
                text = json.optString(KEY_TEXT),
                timestamp = json.optLong(KEY_TIMESTAMP, 0L),
            )
        }
    }
}
