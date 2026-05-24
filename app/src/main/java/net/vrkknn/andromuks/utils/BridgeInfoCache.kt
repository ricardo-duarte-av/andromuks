package net.vrkknn.andromuks.utils

import android.content.Context
import net.vrkknn.andromuks.BuildConfig

/**
 * Cache for bridge protocol avatar URLs and display names.
 *
 * Historically backed by SharedPreferences; now delegates to [RoomMetadataStore]
 * (SQLite). The public API and null/empty-string semantics are unchanged:
 *   - getBridgeAvatarUrl: null  → not cached
 *                          ""    → not bridged
 *                          "x"   → bridged with this mxc:// URL
 *   - getBridgeDisplayName: null → not cached or not bridged
 *                            "x" → bridged with this display name
 *
 * Callers do not need to invoke any initialization here; [RoomMetadataStore]
 * is initialized once at app startup via [AppViewModel.loadCachedProfiles].
 */
object BridgeInfoCache {
    private const val TAG = "BridgeInfoCache"

    // Legacy SharedPreferences keys (retained for one-shot migration).
    private const val PREFS_NAME = "AndromuksAppPrefs"
    private const val BRIDGE_INFO_PREFIX = "bridge_avatar_"
    private const val BRIDGE_DISPLAY_NAME_PREFIX = "bridge_displayname_"
    private const val MIGRATION_FLAG_KEY = "bridge_prefs_migrated_to_sqlite_v1"

    /**
     * Get bridge protocol avatar URL.
     * @return mxc:// URL if room is bridged, empty string if not bridged, null if not cached.
     */
    fun getBridgeAvatarUrl(context: Context, roomId: String): String? {
        val row = RoomMetadataStore.getRow(roomId) ?: return null
        return row.bridgeAvatarMxc
    }

    /**
     * Get bridge protocol display name.
     * @return Display name if bridged, null if not cached or not bridged.
     */
    fun getBridgeDisplayName(context: Context, roomId: String): String? {
        val row = RoomMetadataStore.getRow(roomId) ?: return null
        return row.bridgeDisplayName?.takeIf { it.isNotEmpty() }
    }

    /** True iff we have observed a bridge avatar value (including the explicit "not bridged" sentinel). */
    fun isCached(context: Context, roomId: String): Boolean {
        val row = RoomMetadataStore.getRow(roomId) ?: return false
        return row.bridgeAvatarMxc != null
    }

    /**
     * Save bridge protocol avatar URL for a room.
     * @param avatarUrl mxc:// URL if bridged, empty string if not bridged.
     */
    fun saveBridgeAvatarUrl(context: Context, roomId: String, avatarUrl: String) {
        RoomMetadataStore.upsertBridgeAvatar(roomId, avatarUrl)
    }

    /**
     * Save bridge protocol display name for a room.
     * @param displayName Display name if bridged, empty string if not bridged.
     */
    fun saveBridgeDisplayName(context: Context, roomId: String, displayName: String) {
        RoomMetadataStore.upsertBridgeDisplayName(roomId, displayName)
    }

    /** Remove bridge info for a room (e.g. when room is left). Drops the entire metadata row. */
    fun removeBridgeInfo(context: Context, roomId: String) {
        RoomMetadataStore.remove(roomId)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "$TAG: Removed metadata for $roomId")
        }
    }

    /** Clear all bridge info (e.g. on logout). */
    fun clearAll(context: Context) {
        RoomMetadataStore.clearAll()
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "$TAG: Cleared all bridge info")
        }
    }

    /**
     * One-shot migration of any legacy SharedPreferences bridge entries into [RoomMetadataStore].
     * Safe to call multiple times — guarded by [MIGRATION_FLAG_KEY]. Leaves the SharedPreferences
     * keys in place as a rollback safety net.
     *
     * Must be called after [RoomMetadataStore.initialize].
     */
    fun migrateFromSharedPreferencesIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_FLAG_KEY, false)) return

        val rows = try {
            val all = prefs.all
            val roomIds = HashSet<String>()
            for (key in all.keys) {
                when {
                    key.startsWith(BRIDGE_INFO_PREFIX) -> roomIds.add(key.removePrefix(BRIDGE_INFO_PREFIX))
                    key.startsWith(BRIDGE_DISPLAY_NAME_PREFIX) -> roomIds.add(key.removePrefix(BRIDGE_DISPLAY_NAME_PREFIX))
                }
            }
            roomIds.map { roomId ->
                val avatarKey = BRIDGE_INFO_PREFIX + roomId
                val nameKey = BRIDGE_DISPLAY_NAME_PREFIX + roomId
                RoomMetadataStore.Row(
                    roomId = roomId,
                    name = null,
                    avatarMxc = null,
                    bridgeAvatarMxc = if (prefs.contains(avatarKey)) prefs.getString(avatarKey, "") ?: "" else null,
                    bridgeDisplayName = if (prefs.contains(nameKey)) prefs.getString(nameKey, "") ?: "" else null,
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("Andromuks", "$TAG: migration prep failed", t)
            return
        }

        // Synchronous transaction so the flag is only set after rows are durable.
        RoomMetadataStore.bulkUpsertSync(rows)

        prefs.edit().putBoolean(MIGRATION_FLAG_KEY, true).apply()
        val migrated = rows.size
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "$TAG: migrated $migrated room(s) from SharedPreferences to SQLite")
        }
    }
}
