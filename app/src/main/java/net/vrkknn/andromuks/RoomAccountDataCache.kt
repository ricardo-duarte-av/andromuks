package net.vrkknn.andromuks

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-room account data cache (room-scoped fi.mau.gomuks.preferences, m.tag, etc.).
 * Populated from the account_data block inside each rooms.join entry of sync_complete.
 */
object RoomAccountDataCache {
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, JSONObject>>()

    fun setRoomAccountData(roomId: String, type: String, data: JSONObject) {
        cache.getOrPut(roomId) { ConcurrentHashMap() }[type] = data
    }

    fun getRoomAccountData(roomId: String, type: String): JSONObject? = cache[roomId]?.get(type)

    /**
     * The event_id this user has read up to (m.fully_read), or null if unknown.
     *
     * gomuks may deliver the value either wrapped in a `content` object or as the bare content
     * (just `{"event_id": "..."}`), so both shapes are handled — mirroring how the gomuks-prefs
     * reader in [AccountDataCoordinator] copes with the same ambiguity.
     */
    fun getFullyReadEventId(roomId: String): String? {
        val data = getRoomAccountData(roomId, "m.fully_read") ?: return null
        return (data.optJSONObject("content") ?: data)
            .optString("event_id")
            .takeIf { it.isNotBlank() }
    }

    fun clear() = cache.clear()
}
