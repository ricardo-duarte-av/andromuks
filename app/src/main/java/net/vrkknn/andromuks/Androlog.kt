package net.vrkknn.andromuks

import android.content.Context
import org.json.JSONArray

/**
 * Androlog — a lightweight, app-wide log for cherry-picked events that survive R8 stripping.
 *
 * Unlike [android.util.Log.d] (which R8 removes from release builds), Androlog entries are kept
 * in memory and persisted to SharedPreferences so they can be reviewed on a dedicated Settings
 * screen ("Androlog") and exported, in both debug and release builds.
 *
 * Call it from anywhere via the invoke operator:
 *
 * ```
 * Androlog("Notifications", "Failed to download notification image: HTTP 404 Not found")
 * ```
 *
 * or explicitly with [log]. Each entry records a timestamp, a caller-provided category, and the
 * log text. The store is a process-wide singleton; it must be initialised once with an application
 * Context (done in [AndromuksApplication.onCreate]) before entries can be persisted. Calls made
 * before init are still kept in memory and flushed on the next [init].
 */
object Androlog {

    private const val MAX_ENTRIES = 200
    private const val PREFS_NAME = "AndromuksAndrologPrefs"
    private const val PREFS_KEY = "androlog"

    data class Entry(
        val timestamp: Long,
        val category: String,
        val text: String
    ) {
        fun toJson(): org.json.JSONObject {
            val json = org.json.JSONObject()
            json.put("timestamp", timestamp)
            json.put("category", category)
            json.put("text", text)
            return json
        }

        companion object {
            fun fromJson(json: org.json.JSONObject): Entry {
                return Entry(
                    timestamp = json.getLong("timestamp"),
                    category = json.optString("category"),
                    text = json.optString("text")
                )
            }
        }
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false

    /**
     * Initialise the persistent store. Safe to call multiple times; loads persisted entries the
     * first time and flushes any entries logged before initialisation.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!loaded) {
            loadFromStorage()
            loaded = true
        }
        // Flush anything that was logged before we had a context.
        saveToStorage()
    }

    /** Add a log entry. Usage: `Androlog("Category", "message")`. */
    operator fun invoke(category: String, text: String) = log(category, text)

    fun log(category: String, text: String) {
        val entry = Entry(
            timestamp = System.currentTimeMillis(),
            category = category,
            text = text
        )
        synchronized(lock) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
        }
        // Mirror to logcat so it's also visible in a live dump (Log.i survives R8).
        android.util.Log.i("Androlog", "[$category] $text")
        saveToStorage()
    }

    fun getEntries(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
        saveToStorage()
    }

    private fun loadFromStorage() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY, null) ?: return
            val array = JSONArray(json)
            synchronized(lock) {
                val loadedEntries = ArrayList<Entry>(array.length())
                for (i in 0 until array.length()) {
                    loadedEntries.add(Entry.fromJson(array.getJSONObject(i)))
                }
                // Persisted entries come first, then anything logged before init.
                val pending = entries.toList()
                entries.clear()
                entries.addAll(loadedEntries)
                entries.addAll(pending)
                if (entries.size > MAX_ENTRIES) {
                    val kept = entries.takeLast(MAX_ENTRIES)
                    entries.clear()
                    entries.addAll(kept)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "Androlog: Failed to load from storage", e)
        }
    }

    private fun saveToStorage() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val array = JSONArray()
            val toSave = synchronized(lock) {
                if (entries.size > MAX_ENTRIES) entries.takeLast(MAX_ENTRIES).toList()
                else entries.toList()
            }
            toSave.forEach { array.put(it.toJson()) }
            // apply() not commit(): best-effort, no need to block the caller on fsync.
            prefs.edit().putString(PREFS_KEY, array.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "Androlog: Failed to save to storage", e)
        }
    }
}
