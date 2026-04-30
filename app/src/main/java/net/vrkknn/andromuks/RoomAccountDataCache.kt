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

    fun clear() = cache.clear()
}
