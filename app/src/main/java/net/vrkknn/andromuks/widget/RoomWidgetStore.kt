package net.vrkknn.andromuks.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import org.json.JSONObject

/**
 * Durable binding + snapshot storage for room widgets, and the authority on "does any widget care
 * about this room?".
 *
 * Two very different access patterns share this object, and the split matters:
 *
 * - **Cold reads** (widget render, refresh worker) go to SharedPreferences. They are rare and can
 *   afford the I/O.
 * - **[boundRoomIds] is on the sync hot path.** `SyncIngestor.processRoom` consults it once per room
 *   per `sync_complete`, so it must never touch disk. It answers from a `@Volatile` snapshot loaded
 *   once and rewritten only when a widget is added, reconfigured or deleted. With no widgets
 *   installed the set is empty and every caller pays one `Set.contains` on an empty set.
 */
object RoomWidgetStore {
    private const val TAG = "RoomWidgetStore"
    private const val PREFS_NAME = "AndromuksWidgetPrefs"
    private const val KEY_PREFIX_ROOM = "widget_room_"
    private const val KEY_PREFIX_NAME = "widget_name_"
    private const val KEY_PREFIX_SNAPSHOT = "widget_snapshot_"

    /**
     * How many messages a snapshot holds.
     *
     * There is no per-widget message count: the size the user drags the widget to decides how many
     * are *shown* (`RoomWidget.fittingMessageCount`), so a snapshot always carries the maximum and
     * growing a widget needs no refetch to fill the new space.
     */
    const val MAX_MESSAGE_LIMIT = 10

    /** A one-row widget (4x1) is a legitimate size — it shows the latest message and nothing else. */
    const val MIN_MESSAGE_LIMIT = 1

    /**
     * appWidgetId -> roomId for every configured widget. Replaced wholesale (never mutated in
     * place) so readers on other threads always see a consistent map without locking.
     */
    @Volatile
    private var bindings: Map<Int, String> = emptyMap()

    /** Derived from [bindings]; the hot-path answer for `SyncIngestor`. */
    @Volatile
    private var boundRooms: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Load bindings from disk if we haven't yet. Safe to call from anywhere; the common case after
     * the first call is a single volatile read.
     */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val parsed = prefs(context).all
                .filterKeys { it.startsWith(KEY_PREFIX_ROOM) }
                .mapNotNull { (key, value) ->
                    val id = key.removePrefix(KEY_PREFIX_ROOM).toIntOrNull()
                    val roomId = (value as? String)?.takeIf { it.isNotBlank() }
                    if (id != null && roomId != null) id to roomId else null
                }
                .toMap()
            publish(parsed)
            loaded = true
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Loaded ${parsed.size} widget binding(s) covering ${boundRooms.size} room(s)")
            }
        }
    }

    private fun publish(newBindings: Map<Int, String>) {
        bindings = newBindings
        boundRooms = newBindings.values.toSet()
    }

    /**
     * Room ids with at least one configured widget. **Hot path** — see the class doc. Callers that
     * may run before any widget code has touched the store should use the [Context] overload.
     */
    fun boundRoomIds(): Set<String> = boundRooms

    /** [boundRoomIds] with a one-time load, for callers that might be the first to ask. */
    fun boundRoomIds(context: Context): Set<String> {
        ensureLoaded(context)
        return boundRooms
    }

    /** All widget ids currently bound to [roomId]; empty if the room has no widget. */
    fun widgetIdsForRoom(context: Context, roomId: String): List<Int> {
        ensureLoaded(context)
        return bindings.entries.filter { it.value == roomId }.map { it.key }
    }

    /** All configured widget ids. */
    fun allWidgetIds(context: Context): Set<Int> {
        ensureLoaded(context)
        return bindings.keys
    }

    fun roomIdFor(context: Context, appWidgetId: Int): String? {
        ensureLoaded(context)
        return bindings[appWidgetId]
    }

    /** Persist a widget's room binding plus the display name captured at configuration time. */
    fun bind(context: Context, appWidgetId: Int, roomId: String, roomName: String) {
        ensureLoaded(context)
        prefs(context).edit()
            .putString(KEY_PREFIX_ROOM + appWidgetId, roomId)
            .putString(KEY_PREFIX_NAME + appWidgetId, roomName)
            .apply()
        synchronized(this) { publish(bindings + (appWidgetId to roomId)) }
    }

    /** Drop everything for a deleted widget, including its snapshot. */
    fun unbind(context: Context, appWidgetId: Int) {
        ensureLoaded(context)
        prefs(context).edit()
            .remove(KEY_PREFIX_ROOM + appWidgetId)
            .remove(KEY_PREFIX_NAME + appWidgetId)
            .remove(KEY_PREFIX_SNAPSHOT + appWidgetId)
            .apply()
        synchronized(this) { publish(bindings - appWidgetId) }
    }

    /**
     * The room name recorded when the widget was configured. Used as the last-resort header label
     * when neither `RoomMetadataStore` nor `get_room_summary` can name the room.
     */
    fun configuredRoomName(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(KEY_PREFIX_NAME + appWidgetId, null)?.takeIf { it.isNotBlank() }

    fun readSnapshot(context: Context, appWidgetId: Int): RoomWidgetSnapshot? {
        val raw = prefs(context).getString(KEY_PREFIX_SNAPSHOT + appWidgetId, null) ?: return null
        return try {
            RoomWidgetSnapshot.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable snapshot for widget $appWidgetId: ${e.message}")
            null
        }
    }

    fun writeSnapshot(context: Context, appWidgetId: Int, snapshot: RoomWidgetSnapshot) {
        prefs(context).edit()
            .putString(KEY_PREFIX_SNAPSHOT + appWidgetId, snapshot.toJson().toString())
            .apply()
    }

    /**
     * Every avatar file path referenced by any live snapshot.
     *
     * This is the keep-set for [RoomWidgetAvatars.prune]. It has to span **all** widgets because
     * they share one avatar directory: pruning against a single room's snapshot deletes the files
     * other widgets are currently displaying, which shows up as avatars that vanish and only come
     * back when the widget is re-added.
     */
    fun allReferencedAvatarPaths(context: Context): Set<String> {
        ensureLoaded(context)
        return buildSet {
            bindings.keys.forEach { appWidgetId ->
                val snapshot = readSnapshot(context, appWidgetId) ?: return@forEach
                snapshot.roomAvatarPath?.let { add(it) }
                snapshot.messages.forEach { message -> message.senderAvatarPath?.let { add(it) } }
            }
        }
    }

    /** Write the same snapshot to every widget bound to its room. Returns the ids written. */
    fun writeSnapshotForRoom(context: Context, roomId: String, snapshot: RoomWidgetSnapshot): List<Int> {
        val ids = widgetIdsForRoom(context, roomId)
        ids.forEach { writeSnapshot(context, it, snapshot) }
        return ids
    }

    /**
     * Forget bindings whose widget no longer exists. The host does normally deliver
     * `ACTION_APPWIDGET_DELETED`, but it is not guaranteed across restores and reinstalls, and a
     * stale binding would keep a room permanently in [boundRoomIds] — i.e. keep `SyncIngestor`
     * parsing events for a widget nobody can see.
     */
    fun pruneOrphans(context: Context) {
        ensureLoaded(context)
        val live = try {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(android.content.ComponentName(context, RoomWidgetReceiver::class.java))
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot enumerate live widget ids: ${e.message}")
            return
        }
        val orphans = bindings.keys - live
        if (orphans.isEmpty()) return
        Log.i(TAG, "Pruning ${orphans.size} orphaned widget binding(s): $orphans")
        orphans.forEach { unbind(context, it) }
    }
}
