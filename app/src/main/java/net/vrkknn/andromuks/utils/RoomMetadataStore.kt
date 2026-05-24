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
    private const val DB_VERSION = 1

    private const val TABLE = "room_metadata"
    private const val COL_ROOM_ID = "room_id"
    private const val COL_NAME = "name"
    private const val COL_AVATAR_MXC = "avatar_mxc"
    private const val COL_BRIDGE_AVATAR_MXC = "bridge_avatar_mxc"
    private const val COL_BRIDGE_DISPLAY_NAME = "bridge_display_name"
    private const val COL_UPDATED_AT = "updated_at"

    data class Row(
        val roomId: String,
        val name: String?,
        val avatarMxc: String?,
        val bridgeAvatarMxc: String?,
        val bridgeDisplayName: String?,
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
            arrayOf(COL_ROOM_ID, COL_NAME, COL_AVATAR_MXC, COL_BRIDGE_AVATAR_MXC, COL_BRIDGE_DISPLAY_NAME),
            null, null, null, null, null
        ).use { c ->
            val iRoomId = c.getColumnIndexOrThrow(COL_ROOM_ID)
            val iName = c.getColumnIndexOrThrow(COL_NAME)
            val iAvatar = c.getColumnIndexOrThrow(COL_AVATAR_MXC)
            val iBridgeAvatar = c.getColumnIndexOrThrow(COL_BRIDGE_AVATAR_MXC)
            val iBridgeName = c.getColumnIndexOrThrow(COL_BRIDGE_DISPLAY_NAME)
            while (c.moveToNext()) {
                val roomId = c.getString(iRoomId) ?: continue
                mirror[roomId] = Row(
                    roomId = roomId,
                    name = if (c.isNull(iName)) null else c.getString(iName),
                    avatarMxc = if (c.isNull(iAvatar)) null else c.getString(iAvatar),
                    bridgeAvatarMxc = if (c.isNull(iBridgeAvatar)) null else c.getString(iBridgeAvatar),
                    bridgeDisplayName = if (c.isNull(iBridgeName)) null else c.getString(iBridgeName),
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
    ): Row {
        return mirror.compute(roomId) { _, existing ->
            Row(
                roomId = roomId,
                name = name ?: existing?.name,
                avatarMxc = avatarMxc ?: existing?.avatarMxc,
                bridgeAvatarMxc = bridgeAvatarMxc ?: existing?.bridgeAvatarMxc,
                bridgeDisplayName = bridgeDisplayName ?: existing?.bridgeDisplayName,
            )
        }!!
    }

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
                    $COL_UPDATED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Schema only at v1; nothing to migrate yet.
        }
    }
}
