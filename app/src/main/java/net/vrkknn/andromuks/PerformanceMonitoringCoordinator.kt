package net.vrkknn.andromuks

import android.util.Log
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

/**
 * Owns the app's performance monitoring (Firebase Performance Monitoring).
 *
 * Like crash reporting ([ErrorReportingCoordinator]), this is **opt-in**: automatic collection is
 * disabled in the manifest (`firebase_performance_collection_enabled = false`) and stays off until
 * the user enables it in Settings. The user's choice lives in the `performance_monitoring_enabled`
 * SharedPreference ([SettingsCoordinator]); this coordinator mirrors that flag into the SDK at
 * startup ([applyPersistedState]) and whenever it changes ([setEnabled]).
 *
 * Firebase Performance auto-instruments app start, foreground/background, HTTP requests (via OkHttp),
 * and screen rendering. It does **not** instrument OkHttp *WebSockets* — our primary transport — so
 * [startTrace] / [stopTrace] provide custom traces for that gap (see the `ws_connect` trace in
 * `NetworkUtils.connectToWebsocket`). When collection is disabled, traces are cheap no-ops and are
 * never reported, so call sites need no guard.
 */
internal class PerformanceMonitoringCoordinator(private val vm: AppViewModel) {

    /** Push the persisted opt-in flag into Firebase Performance. Call once on AppViewModel init. */
    fun applyPersistedState() {
        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = vm.performanceMonitoringEnabled
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "PerformanceMonitoringCoordinator: applied collectionEnabled=${vm.performanceMonitoringEnabled}")
        }
    }

    /**
     * Enable or disable performance collection at runtime. Persists via [SettingsCoordinator] and
     * applies the change to the SDK immediately.
     */
    fun setEnabled(enabled: Boolean) {
        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = enabled
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "PerformanceMonitoringCoordinator: setEnabled=$enabled")
        }
    }

    companion object {
        /**
         * Start a named custom trace and return it (or null on error). Stateless and process-wide
         * (FirebasePerformance is a singleton), so layers without an [AppViewModel] handle —
         * `NetworkUtils`, `WebSocketService` — can call it directly. No-op while collection is off.
         */
        fun startTrace(name: String): Trace? = try {
            FirebasePerformance.getInstance().newTrace(name).apply { start() }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("Andromuks", "PerformanceMonitoringCoordinator: startTrace($name) failed", e)
            null
        }

        /** Stop a trace started by [startTrace], optionally tagging a success/outcome attribute. */
        fun stopTrace(trace: Trace?, attribute: Pair<String, String>? = null) {
            if (trace == null) return
            try {
                if (attribute != null) trace.putAttribute(attribute.first, attribute.second)
                trace.stop()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("Andromuks", "PerformanceMonitoringCoordinator: stopTrace failed", e)
            }
        }
    }
}
