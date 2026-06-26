package net.vrkknn.andromuks

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Owns the app's crash / non-fatal error reporting (Firebase Crashlytics).
 *
 * Crash reporting is **opt-in**: automatic collection is disabled in the manifest
 * (`firebase_crashlytics_collection_enabled = false`), and stays off until the user enables it in
 * Settings. The user's choice lives in the `crash_reporting_enabled` SharedPreference
 * ([SettingsCoordinator]); this coordinator mirrors that flag into Crashlytics at startup
 * ([applyPersistedState]) and whenever it changes ([setEnabled]).
 *
 * Crashlytics persists `setCrashlyticsCollectionEnabled` across process restarts on its own, but we
 * re-assert it from our pref on every launch so our SharedPreference remains the single source of
 * truth (e.g. after a reinstall or data-clear that wipes Crashlytics' own state but keeps prefs).
 *
 * When collection is disabled, [recordException] / [log] / [setKey] are cheap no-ops at the
 * Crashlytics level — buffered reports are never sent — so callers don't need to guard each call.
 */
internal class ErrorReportingCoordinator(private val vm: AppViewModel) {

    /** Push the persisted opt-in flag into Crashlytics. Call once on AppViewModel init. */
    fun applyPersistedState() {
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(vm.crashReportingEnabled)
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "ErrorReportingCoordinator: applied collectionEnabled=${vm.crashReportingEnabled}")
        }
    }

    /**
     * Enable or disable crash collection at runtime. Persists via [SettingsCoordinator] and applies
     * the change to Crashlytics immediately. When turning off, any unsent reports are discarded by
     * Crashlytics; when turning on, future crashes/non-fatals are collected.
     */
    fun setEnabled(enabled: Boolean) {
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "ErrorReportingCoordinator: setEnabled=$enabled")
        }
    }

    companion object {
        /**
         * Report a handled (non-fatal) exception, optionally with a breadcrumb [message] describing
         * the site. Stateless and process-wide (Crashlytics is a singleton), so layers without an
         * [AppViewModel] handle — [WebSocketService], [SyncRepository] — can call it directly. A
         * no-op unless the user has opted into crash reporting, so call sites need no guard.
         */
        fun report(throwable: Throwable, message: String? = null) {
            val crashlytics = FirebaseCrashlytics.getInstance()
            if (message != null) crashlytics.log(message)
            crashlytics.recordException(throwable)
        }

        /** Add a breadcrumb to the next crash/non-fatal report. No-op unless the user opted in. */
        fun log(message: String) {
            FirebaseCrashlytics.getInstance().log(message)
        }

        /** Attach a custom key/value pair to subsequent reports (e.g. roomId, connection state). */
        fun setKey(key: String, value: String) {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        }
    }
}
