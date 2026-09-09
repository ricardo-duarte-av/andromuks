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
 *
 * ## Why the store is partitioned by category
 *
 * This was one global list capped at 200 entries, which meant the *chattiest* category silently
 * evicted every other one: a burst of routine `"Notifications"` push traffic could wipe out the
 * handful of `"FCMOpen"` / `"WSDial"` lines that a rare wedge had just written, minutes before the
 * user got a chance to export. Since the whole point of Androlog is post-hoc diagnosis of things
 * that happen once in a blue moon, that made it useless exactly when it mattered.
 *
 * So each category now gets its own ring buffer of [MAX_ENTRIES_PER_CATEGORY]. A category can only
 * ever evict *itself*, and a low-volume category survives indefinitely regardless of what the
 * noisy ones are doing. [MAX_ENTRIES_TOTAL] is a backstop on total storage: when the sum across
 * categories exceeds it, the largest category is trimmed first, so pressure lands on whichever
 * category is actually causing it rather than on the oldest entries globally.
 *
 * Consecutive identical messages within a category collapse into a single entry with a
 * [Entry.repeatCount] instead of appending — retry loops and guard paths that fire in bursts (the
 * dismiss cancel-anyway guard is the pathological one) then cost one slot rather than dozens.
 */
object Androlog {

    /** Per-category ring-buffer depth. A category can only evict its own entries. */
    private const val MAX_ENTRIES_PER_CATEGORY = 300

    /** Backstop on total retained entries; the largest category is trimmed first. */
    private const val MAX_ENTRIES_TOTAL = 2_000

    /** Debounce for the SharedPreferences write; see [scheduleSave]. */
    private const val SAVE_DEBOUNCE_MS = 2_000L

    private const val PREFS_NAME = "AndromuksAndrologPrefs"
    private const val PREFS_KEY = "androlog"

    /**
     * One logged event.
     *
     * @param timestamp when the event was *first* logged.
     * @param lastTimestamp when it was last repeated; equals [timestamp] unless [repeatCount] > 1.
     * @param repeatCount how many consecutive identical messages this entry stands for (1 = normal).
     */
    data class Entry(val timestamp: Long, val category: String, val text: String, val repeatCount: Int = 1, val lastTimestamp: Long = timestamp) {
        fun toJson(): org.json.JSONObject {
            val json = org.json.JSONObject()
            json.put("timestamp", timestamp)
            json.put("category", category)
            json.put("text", text)
            if (repeatCount > 1) {
                json.put("repeatCount", repeatCount)
                json.put("lastTimestamp", lastTimestamp)
            }
            return json
        }

        companion object {
            fun fromJson(json: org.json.JSONObject): Entry {
                val timestamp = json.getLong("timestamp")
                return Entry(
                    timestamp = timestamp,
                    category = json.optString("category"),
                    text = json.optString("text"),
                    repeatCount = json.optInt("repeatCount", 1).coerceAtLeast(1),
                    lastTimestamp = json.optLong("lastTimestamp", timestamp),
                )
            }
        }
    }

    private val lock = Any()

    /**
     * Category → that category's entries, oldest first. A [LinkedHashMap] so the iteration order is
     * stable (first-seen) — only cosmetic, but it keeps exports from reshuffling between runs.
     */
    private val byCategory = LinkedHashMap<String, MutableList<Entry>>()

    @Volatile private var appContext: Context? = null

    @Volatile private var loaded = false

    private val saveExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "androlog-save").apply { isDaemon = true }
        }
    }

    @Volatile private var savePending = false

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
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val bucket = byCategory.getOrPut(category) { mutableListOf() }
            val last = bucket.lastOrNull()
            if (last != null && last.text == text) {
                // Collapse a repeat rather than spending another slot on it.
                bucket[bucket.lastIndex] = last.copy(
                    repeatCount = last.repeatCount + 1,
                    lastTimestamp = now,
                )
            } else {
                bucket.add(Entry(timestamp = now, category = category, text = text))
                if (bucket.size > MAX_ENTRIES_PER_CATEGORY) {
                    bucket.removeAt(0)
                }
                enforceTotalCeilingLocked()
            }
        }
        // Mirror to logcat so it's also visible in a live dump (Log.i survives R8).
        android.util.Log.i("Androlog", "[$category] $text")
        scheduleSave()
    }

    /**
     * Trim the largest category until the total is within [MAX_ENTRIES_TOTAL]. Trimming the biggest
     * rather than the globally-oldest keeps the pressure on whichever category is producing the
     * volume, instead of letting it push out a quiet category's history. Caller holds [lock].
     */
    private fun enforceTotalCeilingLocked() {
        var total = byCategory.values.sumOf { it.size }
        while (total > MAX_ENTRIES_TOTAL) {
            val largest = byCategory.values.maxByOrNull { it.size } ?: return
            if (largest.isEmpty()) return
            largest.removeAt(0)
            total--
        }
    }

    /** All entries across categories, oldest first — the order the UI and the export render in. */
    fun getEntries(): List<Entry> = synchronized(lock) {
        byCategory.values.flatten().sortedBy { it.timestamp }
    }

    /** Category → retained entry count, for the UI's summary row. */
    fun getCategoryCounts(): Map<String, Int> = synchronized(lock) {
        byCategory.entries.associate { (category, list) -> category to list.size }
    }

    fun clear() {
        synchronized(lock) { byCategory.clear() }
        saveToStorage()
    }

    /**
     * Coalesce persistence. [log] used to re-serialise the entire buffer to JSON on the caller's
     * thread on *every* call — tolerable at 200 entries, not at [MAX_ENTRIES_TOTAL], and some
     * callers are hot paths (the WebSocket reader thread). Entries live in memory regardless; this
     * only defers the SharedPreferences write, which was already best-effort (`apply()`).
     */
    private fun scheduleSave() {
        if (appContext == null) return
        if (savePending) return
        savePending = true
        try {
            saveExecutor.schedule(
                {
                    savePending = false
                    saveToStorage()
                },
                SAVE_DEBOUNCE_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Executor shutting down (process teardown) — fall back to an inline save so the
            // entries that prompted it aren't lost.
            savePending = false
            android.util.Log.w("Andromuks", "Androlog: save executor rejected, saving inline", e)
            saveToStorage()
        }
    }

    private fun loadFromStorage() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY, null) ?: return
            val array = JSONArray(json)
            synchronized(lock) {
                // Anything logged before init goes after the persisted history, per category.
                val pending = byCategory.values.flatten().sortedBy { it.timestamp }
                byCategory.clear()
                for (i in 0 until array.length()) {
                    val entry = Entry.fromJson(array.getJSONObject(i))
                    byCategory.getOrPut(entry.category) { mutableListOf() }.add(entry)
                }
                pending.forEach { entry ->
                    byCategory.getOrPut(entry.category) { mutableListOf() }.add(entry)
                }
                byCategory.values.forEach { bucket ->
                    if (bucket.size > MAX_ENTRIES_PER_CATEGORY) {
                        val kept = bucket.takeLast(MAX_ENTRIES_PER_CATEGORY)
                        bucket.clear()
                        bucket.addAll(kept)
                    }
                }
                enforceTotalCeilingLocked()
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
            getEntries().forEach { array.put(it.toJson()) }
            // apply() not commit(): best-effort, no need to block the caller on fsync.
            prefs.edit().putString(PREFS_KEY, array.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "Androlog: Failed to save to storage", e)
        }
    }
}
