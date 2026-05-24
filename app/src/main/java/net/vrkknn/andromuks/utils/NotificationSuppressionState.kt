package net.vrkknn.andromuks.utils

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide in-memory mirror of the two SharedPreferences keys that gate FCM notification
 * suppression: `current_open_room_id` and `app_is_visible`.
 *
 * Background: the writers (AppViewModel.updateCurrentRoomIdInPrefs /
 * updateAppVisibilityInPrefs, plus two sites in ViewModelLifecycleCoordinator) deliberately used
 * SharedPreferences.commit() to win the race with FCMService.shouldSuppressNotification, which
 * reads the same keys. commit() is synchronous: on slow flash it blocks the Main thread for
 * tens to hundreds of ms — every room switch.
 *
 * Fix: AtomicReference / AtomicBoolean are the actual source of truth, updated synchronously
 * (nanoseconds, no I/O). Writers also call .edit().apply() so the value survives process death
 * (crash recovery only — happy-path reads never go to disk). Readers consult the in-memory
 * value first; SharedPreferences is consulted exactly once, lazily, the first time the state is
 * read in a fresh process (FCMService spinning up cold). After that, memory is canonical.
 *
 * Threading: AtomicReference / AtomicBoolean provide the visibility guarantee that two
 * different threads (Main writing, FCMService binder thread reading) need. No locks.
 */
object NotificationSuppressionState {

    private val currentOpenRoomId = AtomicReference<String?>(null)
    private val isAppVisible = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)

    private const val PREFS_NAME = "AndromuksAppPrefs"
    private const val KEY_ROOM_ID = "current_open_room_id"
    private const val KEY_VISIBLE = "app_is_visible"

    /**
     * Cold-start hydration. Reads the persisted values once and seeds the atomics. Subsequent
     * calls are no-ops (CAS on [initialized]). Safe to call from any thread.
     */
    private fun ensureInitialized(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            currentOpenRoomId.set(prefs.getString(KEY_ROOM_ID, null)?.takeIf { it.isNotEmpty() })
            isAppVisible.set(prefs.getBoolean(KEY_VISIBLE, false))
        }
    }

    /**
     * Update both the in-memory value (instant) and SharedPreferences (async, for crash
     * recovery). `null` or empty string means "no room open" and is persisted as a remove.
     */
    fun setCurrentOpenRoomId(context: Context, roomId: String?) {
        val normalized = roomId?.takeIf { it.isNotEmpty() }
        currentOpenRoomId.set(normalized)
        initialized.set(true)  // memory is now authoritative
        val editor = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
        if (normalized != null) editor.putString(KEY_ROOM_ID, normalized)
        else editor.remove(KEY_ROOM_ID)
        editor.apply()  // async — happy-path readers never see the disk write
    }

    fun setAppVisible(context: Context, visible: Boolean) {
        isAppVisible.set(visible)
        initialized.set(true)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VISIBLE, visible)
            .apply()
    }

    /** Returns the currently-open room ID, or `null` if no room is open. */
    fun getCurrentOpenRoomId(context: Context): String? {
        ensureInitialized(context)
        return currentOpenRoomId.get()
    }

    /** Returns whether the app is currently in the foreground. */
    fun isAppVisible(context: Context): Boolean {
        ensureInitialized(context)
        return isAppVisible.get()
    }
}
