package net.vrkknn.andromuks.utils

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.RoomState
import org.json.JSONObject

/**
 * Per-room Matrix state, cached in RAM and persisted to the `room_state` table.
 *
 * ## Why this exists
 *
 * Before this store the app had no per-room state cache at all.
 * `AppViewModel.parseRoomStateFromEvents` scanned the whole `get_room_state` array, kept nine fields
 * in `currentRoomState` — a *single-room slot* nulled on every room switch — and threw the array
 * away. Anything needing another room's state, or the current room's state after a switch, had
 * nothing to read. That is what produced an open padlock on encrypted rooms, permission checks
 * failing open on cold start, and a 1.5s polling loop in UserInfo that could never succeed.
 *
 * ## Staleness contract
 *
 * **The persisted copy is always treated as possibly stale.** It exists so the UI can paint
 * something before the socket is up; it is never authoritative. Callers hydrate from it *and* issue
 * a `get_room_state` regardless of what hydration produced. A response replaces the room's stored
 * state wholesale rather than merging into it — `get_room_state` returns the complete state, so a
 * merge would resurrect state events the room has since removed.
 *
 * ## Two RAM tiers
 *
 * - [parsedStates] — the parsed [RoomState] for **every** known room. Small, and what headers,
 *   permission checks and the room list read. Snapshot-backed, so a composable reading one room
 *   subscribes to that key alone and a late response recomposes only that room's readers.
 * - [rawStates] — the raw per-type JSON, retained only for recently-touched rooms and bounded by
 *   [MAX_RAW_ROOMS]. Rarely-read types (server ACL, join rules) are read back from disk on demand
 *   rather than held for every room forever.
 *
 * Nothing is discarded on disk; RAM is where the trimming happens.
 *
 * ## m.room.member
 *
 * Never stored, in either tier. A large room would be tens of thousands of rows, `get_room_state`
 * is issued with `include_members=false` on the bulk path so the data is usually absent anyway, and
 * a partial member list persisted as though complete is worse than no cache. Membership remains
 * [net.vrkknn.andromuks.RoomMemberCache]'s job.
 */
object RoomStateStore {
    private const val TAG = "RoomStateStore"

    /** Excluded from both tiers and from disk. See the class doc. */
    internal const val MEMBER_TYPE = "m.room.member"

    /**
     * How many rooms keep their raw state JSON in RAM. Rooms beyond this are evicted in
     * least-recently-used order and fall back to a disk read. Sized for "the rooms a user moves
     * between in a session", not for the whole account.
     */
    private const val MAX_RAW_ROOMS = 24

    /** Composite key for the raw tier and the DB primary key: type plus state key. */
    internal fun stateKeyOf(type: String, stateKey: String): String = "$type|$stateKey"

    /**
     * Parsed state per room. Snapshot-backed for per-key Compose subscription.
     *
     * Writes may come from any thread; snapshot state tolerates that. Reads outside composition
     * are plain map reads.
     */
    private val parsedStates = mutableStateMapOf<String, RoomState>()

    /** Raw state JSON for recently-touched rooms only, LRU-bounded to [MAX_RAW_ROOMS]. */
    private val rawStates = object : LinkedHashMap<String, MutableMap<String, JSONObject>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<String, JSONObject>>?): Boolean = size > MAX_RAW_ROOMS
    }

    private val rawLock = Any()

    // ------------------------------------------------------------------ reads

    /**
     * The parsed state for [roomId], or null if this room has never been seen.
     *
     * Null means "no answer yet", never "the room has no state" — callers must not render it as a
     * negative fact. Reading this inside a composable subscribes to that room's entry alone.
     */
    fun getParsed(roomId: String): RoomState? = parsedStates[roomId]

    /** Every parsed room state currently in RAM. */
    fun allParsed(): Map<String, RoomState> = parsedStates.toMap()

    /**
     * The raw content JSON for one state event, or null if absent.
     *
     * Serves from the raw tier when the room is resident. A non-resident room returns null rather
     * than reading disk on the caller's thread — use [loadRawFromDisk] from a coroutine for that.
     */
    fun getRawContent(roomId: String, type: String, stateKey: String = ""): JSONObject? {
        if (type == MEMBER_TYPE) return null
        synchronized(rawLock) {
            return rawStates[roomId]?.get(stateKeyOf(type, stateKey))
        }
    }

    /** True when [roomId]'s raw state is resident in RAM (so [getRawContent] can answer). */
    fun isRawResident(roomId: String): Boolean = synchronized(rawLock) { rawStates.containsKey(roomId) }

    // ------------------------------------------------------------------ RAM population

    /** Replace the parsed state for [roomId]. */
    internal fun putParsed(roomId: String, state: RoomState) {
        parsedStates[roomId] = state
    }

    /**
     * Replace [roomId]'s resident raw state with [events], dropping any member events.
     *
     * Replacement, not merge — see the staleness contract in the class doc.
     */
    internal fun putRaw(roomId: String, events: Map<String, JSONObject>) {
        val filtered = events.filterKeys { !it.startsWith("$MEMBER_TYPE|") }
        synchronized(rawLock) {
            rawStates[roomId] = filtered.toMutableMap()
        }
    }

    /** Merge a subset of state into [roomId]'s raw tier, if resident. Used by targeted fetches. */
    internal fun mergeRaw(roomId: String, events: Map<String, JSONObject>) {
        val filtered = events.filterKeys { !it.startsWith("$MEMBER_TYPE|") }
        if (filtered.isEmpty()) return
        synchronized(rawLock) {
            rawStates[roomId]?.putAll(filtered)
        }
    }

    /** Forget everything about [roomId]. Called when its room is pruned or the account changes. */
    fun forgetRoom(roomId: String) {
        parsedStates.remove(roomId)
        synchronized(rawLock) { rawStates.remove(roomId) }
    }

    /** Forget every room. Logout, or a hard reset. */
    fun clearMemory() {
        parsedStates.clear()
        synchronized(rawLock) { rawStates.clear() }
    }

    // ------------------------------------------------------------------ hydration

    /** One persisted state row, already validated and parsed. */
    private class StateRow(val roomId: String, val key: String, val content: JSONObject)

    /**
     * Read the cursor's current row, or null if it is unusable — a null column, a member event
     * (never cached), or content that will not parse. A single unparseable row must not abort
     * hydration for the whole account.
     */
    private fun readRow(cursor: Cursor, roomIdx: Int, typeIdx: Int, keyIdx: Int, contentIdx: Int): StateRow? {
        val roomId = cursor.getString(roomIdx) ?: return null
        val type = cursor.getString(typeIdx) ?: return null
        if (type == MEMBER_TYPE) return null
        val content = cursor.getString(contentIdx) ?: return null
        val parsed = try {
            JSONObject(content)
        } catch (t: Throwable) {
            Log.w(TAG, "Skipping unparseable $type in $roomId", t)
            return null
        }
        return StateRow(roomId, stateKeyOf(type, cursor.getString(keyIdx) ?: ""), parsed)
    }

    /**
     * Load persisted state into RAM. Called from [RoomMetadataStore.initialize], on its thread,
     * inside its single catch-all — a corrupt table must degrade to an empty cache, never a crash.
     *
     * Only the raw rows for the most recently updated rooms are held; the parsed tier is filled by
     * the caller re-parsing them, so this deliberately does not duplicate the parser here.
     *
     * Skip-existing semantics, matching RoomMetadataStore's own mirror hydration: a write that
     * raced ahead of hydration is fresher than anything on disk and must win.
     */
    internal fun hydrateFromDisk(db: SQLiteDatabase) {
        var rows = 0
        try {
            db.query(
                RoomMetadataStore.STATE_TABLE,
                arrayOf(
                    RoomMetadataStore.COL_STATE_ROOM_ID,
                    RoomMetadataStore.COL_STATE_TYPE,
                    RoomMetadataStore.COL_STATE_KEY,
                    RoomMetadataStore.COL_STATE_CONTENT,
                ),
                null,
                null,
                null,
                null,
                "${RoomMetadataStore.COL_STATE_UPDATED_AT} DESC",
            ).use { cursor ->
                val roomIdx = cursor.getColumnIndexOrThrow(RoomMetadataStore.COL_STATE_ROOM_ID)
                val typeIdx = cursor.getColumnIndexOrThrow(RoomMetadataStore.COL_STATE_TYPE)
                val keyIdx = cursor.getColumnIndexOrThrow(RoomMetadataStore.COL_STATE_KEY)
                val contentIdx = cursor.getColumnIndexOrThrow(RoomMetadataStore.COL_STATE_CONTENT)
                val loaded = mutableMapOf<String, MutableMap<String, JSONObject>>()
                while (cursor.moveToNext()) {
                    val row = readRow(cursor, roomIdx, typeIdx, keyIdx, contentIdx)
                    // Bounded by the same LRU as the live tier; rows are ordered newest-first, so
                    // this keeps the most recently updated rooms and drops the tail.
                    val admissible = row != null &&
                        (loaded.size < MAX_RAW_ROOMS || loaded.containsKey(row.roomId))
                    if (admissible) {
                        loaded.getOrPut(row.roomId) { mutableMapOf() }[row.key] = row.content
                        rows++
                    }
                }
                synchronized(rawLock) {
                    for ((roomId, events) in loaded) {
                        if (rawStates.containsKey(roomId)) continue
                        rawStates[roomId] = events
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hydrateFromDisk failed", t)
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Hydrated $rows state rows for ${synchronized(rawLock) { rawStates.size }} rooms")
        }
    }
}
