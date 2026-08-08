package net.vrkknn.andromuks.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent metadata for rooms: name, avatar, bridge info.
 *
 * Null vs empty-string convention (matches the prior BridgeInfoCache):
 *   - column = NULL  → never observed (treat as unknown, fall back to other sources)
 *   - column = ""    → server explicitly says blank / not bridged
 *   - column = "x"   → real value
 *
 * Architecture: in-memory mirror is authoritative for reads. SQLite is the
 * crash-safe persistence layer. [initialize] hydrates the mirror from disk;
 * every write updates the mirror synchronously and schedules the disk write
 * on a background coroutine. Callers therefore never block on disk I/O after
 * startup — matching the codebase's "all-in-RAM" operating mode.
 */
object RoomMetadataStore {
    private const val TAG = "RoomMetadataStore"

    private const val DB_NAME = "room_metadata.db"
    private const val DB_VERSION = 5

    private const val TABLE = "room_metadata"
    private const val COL_ROOM_ID = "room_id"
    private const val COL_NAME = "name"
    private const val COL_AVATAR_MXC = "avatar_mxc"
    private const val COL_BRIDGE_AVATAR_MXC = "bridge_avatar_mxc"
    private const val COL_BRIDGE_DISPLAY_NAME = "bridge_display_name"
    private const val COL_UPDATED_AT = "updated_at"

    // v2: last-known sortingTimestamp (matches RoomItem.sortingTimestamp). Persisted so the
    // cached room list can be sorted descending on cold start, before sync_complete updates.
    // 0 = unknown; the room list sort treats unknown rooms as oldest.
    private const val COL_SORT_TS = "sort_ts"

    // v3: whether the conversation shortcut for this room was last published with a real avatar
    // icon (1) vs a lettermark fallback (0/unknown). Persisted so a freshly-constructed
    // ConversationsApi (e.g. in NotificationImageWorker) knows the shortcut already carries its
    // avatar and skips a redundant in-place icon rebuild on every notification. See
    // ConversationsApi.updateSingleShortcut().
    private const val COL_SHORTCUT_HAS_AVATAR = "shortcut_has_avatar"

    // v4: the sticky room-list section flags (mirror RoomItem.isFavourite / isLowPriority /
    // isDirectMessage). Persisted so the Favourites / DM / low-priority pseudo-tabs are correct on
    // cold start — before any sync_complete. Without this, a catchup reconnect (which only re-sends
    // rooms that CHANGED) leaves every unchanged favourite/DM room flag-less after process death,
    // dropping them from those tabs. 0 = false/unknown, 1 = true.
    private const val COL_IS_FAVOURITE = "is_favourite"
    private const val COL_IS_LOW_PRIORITY = "is_low_priority"
    private const val COL_IS_DIRECT = "is_direct"

    // v5: the bridge's protocol identity (BridgeInfo.protocolId, e.g. "discord"). The Bridges tab
    // groups pseudo-spaces on this rather than on the avatar URL, so it has to survive process
    // death alongside the avatar — otherwise every bridged room falls back to avatar-grouping on
    // cold start and the tab reshuffles once get_room_state catches up.
    private const val COL_BRIDGE_PROTOCOL_ID = "bridge_protocol_id"

    // v5: whether [COL_BRIDGE_AVATAR_MXC] holds the protocol's own logo (1) or a per-instance
    // network/channel icon (0). Lets the Bridges tab pick a stable pseudo-space icon when rooms in
    // one protocol group carry different avatars. See RoomItem.bridgeAvatarIsProtocolLevel.
    private const val COL_BRIDGE_AVATAR_IS_PROTOCOL = "bridge_avatar_is_protocol"

    data class Row(
        val roomId: String,
        val name: String?,
        val avatarMxc: String?,
        val bridgeAvatarMxc: String?,
        val bridgeDisplayName: String?,
        val bridgeProtocolId: String? = null,
        val bridgeAvatarIsProtocol: Boolean = false,
        val sortTs: Long = 0L,
        val shortcutHasAvatar: Boolean = false,
        val isFavourite: Boolean = false,
        val isLowPriority: Boolean = false,
        val isDirect: Boolean = false,
    )

    private val initialized = AtomicBoolean(false)
    private var helper: Helper? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Authoritative in-memory mirror. Populated from disk during [initialize] and kept in
     * sync by every mutation. All [getRow]/[loadAll] reads serve from here without touching
     * disk — this prevents StrictMode disk-read violations on the main thread during sync
     * processing (which reads bridge info per room on every sync_complete).
     */
    private val mirror = ConcurrentHashMap<String, Row>()

    /**
     * Open the database, enable WAL, and hydrate the in-memory mirror. Safe to call
     * multiple times; subsequent calls are no-ops. Must be called before any other method.
     */
    fun initialize(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            helper = Helper(context.applicationContext)
            try {
                val db = helper!!.writableDatabase
                db.enableWriteAheadLogging()
                hydrateMirrorFromDisk(db)
            } catch (t: Throwable) {
                Log.w(TAG, "initialize failed", t)
            }
        }
    }

    private fun hydrateMirrorFromDisk(db: SQLiteDatabase) {
        db.query(
            TABLE,
            arrayOf(
                COL_ROOM_ID,
                COL_NAME,
                COL_AVATAR_MXC,
                COL_BRIDGE_AVATAR_MXC,
                COL_BRIDGE_DISPLAY_NAME,
                COL_BRIDGE_PROTOCOL_ID,
                COL_BRIDGE_AVATAR_IS_PROTOCOL,
                COL_SORT_TS,
                COL_SHORTCUT_HAS_AVATAR,
                COL_IS_FAVOURITE,
                COL_IS_LOW_PRIORITY,
                COL_IS_DIRECT,
            ),
            null,
            null,
            null,
            null,
            null,
        ).use { c ->
            val iRoomId = c.getColumnIndexOrThrow(COL_ROOM_ID)
            val iName = c.getColumnIndexOrThrow(COL_NAME)
            val iAvatar = c.getColumnIndexOrThrow(COL_AVATAR_MXC)
            val iBridgeAvatar = c.getColumnIndexOrThrow(COL_BRIDGE_AVATAR_MXC)
            val iBridgeName = c.getColumnIndexOrThrow(COL_BRIDGE_DISPLAY_NAME)
            val iBridgeProtocolId = c.getColumnIndexOrThrow(COL_BRIDGE_PROTOCOL_ID)
            val iBridgeAvatarIsProtocol = c.getColumnIndexOrThrow(COL_BRIDGE_AVATAR_IS_PROTOCOL)
            val iSortTs = c.getColumnIndexOrThrow(COL_SORT_TS)
            val iShortcutAvatar = c.getColumnIndexOrThrow(COL_SHORTCUT_HAS_AVATAR)
            val iFavourite = c.getColumnIndexOrThrow(COL_IS_FAVOURITE)
            val iLowPriority = c.getColumnIndexOrThrow(COL_IS_LOW_PRIORITY)
            val iDirect = c.getColumnIndexOrThrow(COL_IS_DIRECT)
            while (c.moveToNext()) {
                val roomId = c.getString(iRoomId) ?: continue
                // Skip-existing: never clobber a Row a backend write already populated. hydrate runs
                // once per process at initialize() (mirror is normally empty here, so this is free on
                // the common path); the guard only matters if a sync/command write raced ahead of
                // initialize(), in which case the fresh in-memory value must win over the stale disk
                // row rather than be overwritten by this unconditional load. Mirrors the skip-existing
                // semantics of RoomListCache.hydrateFromDisk so the two hydrators behave consistently.
                if (mirror.containsKey(roomId)) continue
                mirror[roomId] = Row(
                    roomId = roomId,
                    name = if (c.isNull(iName)) null else c.getString(iName),
                    avatarMxc = if (c.isNull(iAvatar)) null else c.getString(iAvatar),
                    bridgeAvatarMxc = if (c.isNull(iBridgeAvatar)) null else c.getString(iBridgeAvatar),
                    bridgeDisplayName = if (c.isNull(iBridgeName)) null else c.getString(iBridgeName),
                    bridgeProtocolId = if (c.isNull(iBridgeProtocolId)) null else c.getString(iBridgeProtocolId),
                    bridgeAvatarIsProtocol =
                    !c.isNull(iBridgeAvatarIsProtocol) && c.getInt(iBridgeAvatarIsProtocol) != 0,
                    sortTs = if (c.isNull(iSortTs)) 0L else c.getLong(iSortTs),
                    shortcutHasAvatar = !c.isNull(iShortcutAvatar) && c.getInt(iShortcutAvatar) != 0,
                    isFavourite = !c.isNull(iFavourite) && c.getInt(iFavourite) != 0,
                    isLowPriority = !c.isNull(iLowPriority) && c.getInt(iLowPriority) != 0,
                    isDirect = !c.isNull(iDirect) && c.getInt(iDirect) != 0,
                )
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "hydrated mirror with ${mirror.size} rows")
    }

    /** Snapshot of every known room's metadata. Serves from RAM. */
    fun loadAll(): Map<String, Row> = HashMap(mirror)

    /** Single-row fetch. Serves from RAM. */
    fun getRow(roomId: String): Row? = mirror[roomId]

    /**
     * Upsert room name and avatar. Null arguments are interpreted as
     * "do not touch this column" (preserves existing value).
     * Pass empty string "" to explicitly persist a blank value.
     */
    fun upsertNameAvatar(roomId: String, name: String?, avatarMxc: String?) {
        if (name == null && avatarMxc == null) return
        // Mirror update is synchronous so subsequent reads see the new value immediately.
        val updated = mergeIntoMirror(roomId, name = name, avatarMxc = avatarMxc)
        if (helper == null) return
        ioScope.launch {
            writePartial(updated.roomId) { values ->
                if (name != null) values.put(COL_NAME, name)
                if (avatarMxc != null) values.put(COL_AVATAR_MXC, avatarMxc)
            }
        }
    }

    /** Upsert bridge avatar URL. Pass "" for non-bridged rooms. */
    fun upsertBridgeAvatar(roomId: String, bridgeAvatarMxc: String) {
        mergeIntoMirror(roomId, bridgeAvatarMxc = bridgeAvatarMxc)
        if (helper == null) return
        ioScope.launch {
            writePartial(roomId) { values ->
                values.put(COL_BRIDGE_AVATAR_MXC, bridgeAvatarMxc)
            }
        }
    }

    /**
     * Upsert the bridge protocol identity (`BridgeInfo.protocolId`) and whether the room's stored
     * bridge avatar is the protocol's own logo. Written together because both are derived from the
     * same parse of one bridge state event. Pass "" / false for non-bridged rooms.
     */
    fun upsertBridgeIdentity(roomId: String, bridgeProtocolId: String, avatarIsProtocolLevel: Boolean) {
        val existing = mirror[roomId]
        if (existing != null &&
            existing.bridgeProtocolId == bridgeProtocolId &&
            existing.bridgeAvatarIsProtocol == avatarIsProtocolLevel
        ) {
            return
        }
        mergeIntoMirror(
            roomId,
            bridgeProtocolId = bridgeProtocolId,
            bridgeAvatarIsProtocol = avatarIsProtocolLevel,
        )
        if (helper == null) return
        ioScope.launch {
            writePartial(roomId) { values ->
                values.put(COL_BRIDGE_PROTOCOL_ID, bridgeProtocolId)
                values.put(COL_BRIDGE_AVATAR_IS_PROTOCOL, if (avatarIsProtocolLevel) 1 else 0)
            }
        }
    }

    /** Upsert bridge display name. Pass "" for non-bridged rooms. */
    fun upsertBridgeDisplayName(roomId: String, bridgeDisplayName: String) {
        mergeIntoMirror(roomId, bridgeDisplayName = bridgeDisplayName)
        if (helper == null) return
        ioScope.launch {
            writePartial(roomId) { values ->
                values.put(COL_BRIDGE_DISPLAY_NAME, bridgeDisplayName)
            }
        }
    }

    /**
     * Upsert whether the conversation shortcut for this room currently carries a real avatar
     * icon (true) or a lettermark fallback (false). Lets the notification worker avoid a
     * redundant icon rebuild when the shortcut already has its avatar.
     */
    fun upsertShortcutHasAvatar(roomId: String, hasAvatar: Boolean) {
        val existing = mirror[roomId]
        if (existing != null && existing.shortcutHasAvatar == hasAvatar) return
        mergeIntoMirror(roomId, shortcutHasAvatar = hasAvatar)
        if (helper == null) return
        ioScope.launch {
            writePartial(roomId) { values ->
                values.put(COL_SHORTCUT_HAS_AVATAR, if (hasAvatar) 1 else 0)
            }
        }
    }

    /**
     * One room's metadata update for [upsertMetadataBatchAsync]. Mirrors the columns
     * [RoomListCache] persists per sync_complete. Null [name]/[avatarMxc] means "leave that
     * column untouched"; [sortTs] only advances forward (older/zero values are dropped).
     */
    data class MetaUpdate(
        val roomId: String,
        val name: String? = null,
        val avatarMxc: String? = null,
        val sortTs: Long? = null,
        // Sticky section flags. Null = "leave the column untouched"; a non-null value is written
        // only when it differs from the mirror (so a steady favourite doesn't rewrite every sync).
        val isFavourite: Boolean? = null,
        val isLowPriority: Boolean? = null,
        val isDirect: Boolean? = null,
    )

    /**
     * Hot-path batched upsert: merges every update into the in-memory mirror synchronously
     * (so reads see new values immediately), then persists ALL changed rows in a SINGLE
     * background transaction.
     *
     * This replaces the per-room fan-out of [upsertNameAvatar] + [upsertSortTs] — each of which
     * launches its own coroutine and runs its own INSERT/UPDATE. On a battery-saver reconnect
     * that re-sends ~500 rooms, that fan-out meant ~1000 individual SQLite writes hammering the
     * IO writer in the exact window the user wants responsiveness; this collapses it to one
     * transaction. Forward-only [sortTs] semantics match [upsertSortTs].
     */
    fun upsertMetadataBatchAsync(updates: List<MetaUpdate>) {
        if (updates.isEmpty()) return
        // Merge into the mirror synchronously, applying the forward-only sortTs guard. Collect
        // the rows that actually need a disk write (mirror-merge is cheap; a non-advancing sortTs
        // with no name/avatar is a pure no-op and is skipped entirely).
        val toPersist = ArrayList<MetaUpdate>(updates.size)
        for (u in updates) {
            val existing = mirror[u.roomId]
            val effectiveSortTs = u.sortTs?.takeIf { it > 0L && (existing == null || existing.sortTs < it) }
            // Only treat a flag as changed (worth a write) when it actually differs from the mirror.
            val favChanged = u.isFavourite != null && (existing == null || existing.isFavourite != u.isFavourite)
            val lowChanged = u.isLowPriority != null && (existing == null || existing.isLowPriority != u.isLowPriority)
            val dmChanged = u.isDirect != null && (existing == null || existing.isDirect != u.isDirect)
            if (u.name == null && u.avatarMxc == null && effectiveSortTs == null &&
                !favChanged && !lowChanged && !dmChanged
            ) {
                continue
            }
            mergeIntoMirror(
                roomId = u.roomId,
                name = u.name,
                avatarMxc = u.avatarMxc,
                sortTs = effectiveSortTs,
                isFavourite = u.isFavourite,
                isLowPriority = u.isLowPriority,
                isDirect = u.isDirect,
            )
            // Persist only the flags that changed (leave unchanged columns untouched) and drop a
            // non-advancing sortTs.
            toPersist.add(
                u.copy(
                    sortTs = effectiveSortTs,
                    isFavourite = u.isFavourite.takeIf { favChanged },
                    isLowPriority = u.isLowPriority.takeIf { lowChanged },
                    isDirect = u.isDirect.takeIf { dmChanged },
                ),
            )
        }
        if (toPersist.isEmpty() || helper == null) return
        ioScope.launch {
            val db = helper?.writableDatabase ?: return@launch
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                for (u in toPersist) {
                    val values = ContentValues().apply {
                        put(COL_ROOM_ID, u.roomId)
                        put(COL_UPDATED_AT, now)
                        if (u.name != null) put(COL_NAME, u.name)
                        if (u.avatarMxc != null) put(COL_AVATAR_MXC, u.avatarMxc)
                        if (u.sortTs != null) put(COL_SORT_TS, u.sortTs)
                        if (u.isFavourite != null) put(COL_IS_FAVOURITE, if (u.isFavourite) 1 else 0)
                        if (u.isLowPriority != null) put(COL_IS_LOW_PRIORITY, if (u.isLowPriority) 1 else 0)
                        if (u.isDirect != null) put(COL_IS_DIRECT, if (u.isDirect) 1 else 0)
                    }
                    val inserted = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (inserted == -1L) {
                        values.remove(COL_ROOM_ID)
                        db.update(TABLE, values, "$COL_ROOM_ID = ?", arrayOf(u.roomId))
                    }
                }
                db.setTransactionSuccessful()
            } catch (t: Throwable) {
                Log.w(TAG, "upsertMetadataBatchAsync failed (${toPersist.size} rows)", t)
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Delete the row for [roomId] (e.g. on room leave). */
    fun remove(roomId: String) {
        mirror.remove(roomId)
        if (helper == null) return
        ioScope.launch {
            try {
                helper?.writableDatabase?.delete(TABLE, "$COL_ROOM_ID = ?", arrayOf(roomId))
            } catch (t: Throwable) {
                Log.w(TAG, "remove failed for $roomId", t)
            }
        }
    }

    /**
     * Synchronous bulk upsert in a single transaction. Used for the one-shot
     * SharedPreferences → SQLite migration; not appropriate for hot paths.
     *
     * Each entry's non-null fields are persisted (null fields are left untouched
     * on existing rows; on new rows null fields stay NULL). Mirror is updated
     * synchronously too.
     */
    fun bulkUpsertSync(rows: List<Row>) {
        if (rows.isEmpty()) return
        for (row in rows) {
            mergeIntoMirror(
                roomId = row.roomId,
                name = row.name,
                avatarMxc = row.avatarMxc,
                bridgeAvatarMxc = row.bridgeAvatarMxc,
                bridgeDisplayName = row.bridgeDisplayName,
                bridgeProtocolId = row.bridgeProtocolId,
                bridgeAvatarIsProtocol = row.bridgeAvatarIsProtocol,
            )
        }
        val db = helper?.writableDatabase ?: return
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (row in rows) {
                val values = ContentValues().apply {
                    put(COL_ROOM_ID, row.roomId)
                    put(COL_UPDATED_AT, now)
                    if (row.name != null) put(COL_NAME, row.name)
                    if (row.avatarMxc != null) put(COL_AVATAR_MXC, row.avatarMxc)
                    if (row.bridgeAvatarMxc != null) put(COL_BRIDGE_AVATAR_MXC, row.bridgeAvatarMxc)
                    if (row.bridgeDisplayName != null) put(COL_BRIDGE_DISPLAY_NAME, row.bridgeDisplayName)
                    if (row.bridgeProtocolId != null) put(COL_BRIDGE_PROTOCOL_ID, row.bridgeProtocolId)
                    if (row.bridgeAvatarIsProtocol) put(COL_BRIDGE_AVATAR_IS_PROTOCOL, 1)
                }
                val inserted = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted == -1L) {
                    values.remove(COL_ROOM_ID)
                    db.update(TABLE, values, "$COL_ROOM_ID = ?", arrayOf(row.roomId))
                }
            }
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            Log.w(TAG, "bulkUpsertSync failed", t)
        } finally {
            db.endTransaction()
        }
    }

    /** Wipe the entire table (e.g. on logout). */
    fun clearAll() {
        mirror.clear()
        if (helper == null) return
        ioScope.launch {
            try {
                helper?.writableDatabase?.delete(TABLE, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "clearAll failed", t)
            }
        }
    }

    /**
     * Merge the supplied non-null fields into the in-memory mirror, preserving
     * existing values for any field passed as null. Returns the resulting Row.
     *
     * Uses [ConcurrentHashMap.compute] for atomicity — two concurrent writers
     * targeting the same roomId can never lose a field.
     */
    private fun mergeIntoMirror(
        roomId: String,
        name: String? = null,
        avatarMxc: String? = null,
        bridgeAvatarMxc: String? = null,
        bridgeDisplayName: String? = null,
        bridgeProtocolId: String? = null,
        bridgeAvatarIsProtocol: Boolean? = null,
        sortTs: Long? = null,
        shortcutHasAvatar: Boolean? = null,
        isFavourite: Boolean? = null,
        isLowPriority: Boolean? = null,
        isDirect: Boolean? = null,
    ): Row = mirror.compute(roomId) { _, existing ->
        Row(
            roomId = roomId,
            name = name ?: existing?.name,
            avatarMxc = avatarMxc ?: existing?.avatarMxc,
            bridgeAvatarMxc = bridgeAvatarMxc ?: existing?.bridgeAvatarMxc,
            bridgeDisplayName = bridgeDisplayName ?: existing?.bridgeDisplayName,
            bridgeProtocolId = bridgeProtocolId ?: existing?.bridgeProtocolId,
            bridgeAvatarIsProtocol = bridgeAvatarIsProtocol ?: existing?.bridgeAvatarIsProtocol ?: false,
            sortTs = sortTs ?: existing?.sortTs ?: 0L,
            shortcutHasAvatar = shortcutHasAvatar ?: existing?.shortcutHasAvatar ?: false,
            isFavourite = isFavourite ?: existing?.isFavourite ?: false,
            isLowPriority = isLowPriority ?: existing?.isLowPriority ?: false,
            isDirect = isDirect ?: existing?.isDirect ?: false,
        )
    }!!

    private fun writePartial(roomId: String, fill: (ContentValues) -> Unit) {
        val db = helper?.writableDatabase ?: return
        val values = ContentValues().apply {
            put(COL_ROOM_ID, roomId)
            put(COL_UPDATED_AT, System.currentTimeMillis())
            fill(this)
        }
        try {
            // INSERT OR IGNORE then UPDATE preserves columns not present in [values],
            // which is the semantic we need for partial upserts.
            val inserted = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted == -1L) {
                values.remove(COL_ROOM_ID)
                db.update(TABLE, values, "$COL_ROOM_ID = ?", arrayOf(roomId))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "writePartial failed for $roomId", t)
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ROOM_ID TEXT PRIMARY KEY NOT NULL,
                    $COL_NAME TEXT,
                    $COL_AVATAR_MXC TEXT,
                    $COL_BRIDGE_AVATAR_MXC TEXT,
                    $COL_BRIDGE_DISPLAY_NAME TEXT,
                    $COL_BRIDGE_PROTOCOL_ID TEXT,
                    $COL_BRIDGE_AVATAR_IS_PROTOCOL INTEGER NOT NULL DEFAULT 0,
                    $COL_UPDATED_AT INTEGER NOT NULL,
                    $COL_SORT_TS INTEGER NOT NULL DEFAULT 0,
                    $COL_SHORTCUT_HAS_AVATAR INTEGER NOT NULL DEFAULT 0,
                    $COL_IS_FAVOURITE INTEGER NOT NULL DEFAULT 0,
                    $COL_IS_LOW_PRIORITY INTEGER NOT NULL DEFAULT 0,
                    $COL_IS_DIRECT INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 → v2: add sort_ts column for cached room list ordering.
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_SORT_TS INTEGER NOT NULL DEFAULT 0")
            }
            // v2 → v3: add shortcut_has_avatar so the notification worker can skip redundant
            // shortcut icon rebuilds when the avatar is already published.
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_SHORTCUT_HAS_AVATAR INTEGER NOT NULL DEFAULT 0")
            }
            // v3 → v4: add the sticky section flags so Favourites / DM / low-priority tabs are
            // correct on cold start (before sync). Existing rows default to 0/false and are
            // corrected by the first sync_complete that carries their m.tag / dm_user_id.
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_IS_FAVOURITE INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_IS_LOW_PRIORITY INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_IS_DIRECT INTEGER NOT NULL DEFAULT 0")
            }
            // v4 → v5: add the bridge protocol id the Bridges tab now groups on, plus the flag
            // saying whether the stored avatar is the protocol's own logo. Existing rows stay
            // NULL/0 and fall back to avatar-URL grouping until the next get_room_state fills
            // them in.
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_BRIDGE_PROTOCOL_ID TEXT")
                db.execSQL(
                    "ALTER TABLE $TABLE ADD COLUMN $COL_BRIDGE_AVATAR_IS_PROTOCOL INTEGER NOT NULL DEFAULT 0",
                )
                // Invalidate the rooms the pre-v5 parser got WRONG, so they are re-fetched once.
                //
                // That parser only ever looked at `protocol.avatar_url`, so a bridge publishing its
                // icon on MSC2346's `network` descriptor (OOYE, for one) was recorded as
                // bridge_avatar_mxc = "" — the "not bridged" sentinel. BridgeInfoCache.isCached()
                // reads exactly that column, so loadAllRoomStatesAfterInitComplete would skip those
                // rooms forever and the new network-descriptor parsing would never run on them.
                //
                // Setting the column back to NULL restores "never observed", which makes isCached()
                // false and puts the room back in the get_room_state queue. The discriminator is
                // precise: the old code persisted a display name whenever it saw a bridge event at
                // all, so ""-avatar WITH a display name means "bridged but we found no icon" (wrong,
                // re-fetch), while ""-avatar with no display name means "genuinely not bridged"
                // (correct, leave cached). Without that second clause this would re-request state
                // for every unbridged room in the account.
                db.execSQL(
                    """
                    UPDATE $TABLE SET $COL_BRIDGE_AVATAR_MXC = NULL
                    WHERE $COL_BRIDGE_AVATAR_MXC = ''
                      AND $COL_BRIDGE_DISPLAY_NAME IS NOT NULL
                      AND $COL_BRIDGE_DISPLAY_NAME != ''
                    """.trimIndent(),
                )
            }
        }

        /**
         * Drop and recreate rather than throw.
         *
         * The default [SQLiteOpenHelper] behaviour on a downgrade is to raise
         * SQLiteDowngradeFailedException, which for this database means the app cannot open its
         * store at all — every read degrades to empty and the room list paints blank on cold start.
         * That happens to anyone who runs a branch that bumped [DB_VERSION] and then switches back,
         * which is a routine thing to do while developing.
         *
         * Everything here is a paint-time optimisation reconstructible from the next sync, so
         * discarding it is cheap and always safe. Never store anything in this database for which
         * that stops being true.
         */
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.w(TAG, "Downgrade $oldVersion → $newVersion; recreating (contents are rebuildable from sync)")
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
