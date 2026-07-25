package net.vrkknn.andromuks.utils

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared [OkHttpClient] for the whole app, plus a helper for deriving variants.
 *
 * ## Why
 *
 * Every `OkHttpClient()` gets its own [okhttp3.ConnectionPool], its own [okhttp3.Dispatcher]
 * (and therefore its own thread pool), and shares no TLS session cache with any other. The app
 * had ~15 of them, four constructed *per invocation* inside media download helpers — all talking
 * to the **same** gomuks host with the **same** auth cookie.
 *
 * The practical cost is on the user's battery: "save this video" paid a fresh TCP handshake plus
 * a full TLS handshake instead of reusing an already-warm pooled connection, which means dragging
 * the radio out of idle and burning CPU on the handshake, every time. OkHttp's own documentation
 * is explicit that clients should be shared.
 *
 * ## How to use
 *
 * - Need the defaults? Use [shared].
 * - Need different timeouts or an extra interceptor? Use [derived], **not** `OkHttpClient()`.
 *   `newBuilder()` keeps the parent's connection pool and dispatcher, so a derived client still
 *   reuses connections and threads — it just layers its own configuration on top.
 *
 * Note: [ImageLoaderSingleton] deliberately derives its own client here so Coil's image traffic
 * shares the same pool as everything else while keeping its auth-cookie / `?encrypted=`
 * interceptors and its own concurrency limits.
 *
 * ## Two clients that intentionally do NOT use this
 *
 * Both live in `WebSocketService`, and both would be actively harmful to share:
 *
 * - **The WebSocket client.** A WebSocket occupies a [okhttp3.Dispatcher] slot for the entire
 *   lifetime of the connection. Putting it on the shared dispatcher means one permanently-parked
 *   call competing with every ordinary request for the concurrency budget.
 * - **`healthCheckClient`.** It calls `connectionPool.evictAll()` on network changes to clear
 *   stale DNS/connections. On the shared pool that would tear down every other component's warm
 *   connections as a side effect of a health check.
 */
object HttpClientProvider {
    // Matches the timeouts the ad-hoc clients were using. Deliberately modest: these are
    // interactive requests against the user's own homeserver, not background bulk transfers.
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    /**
     * The process-wide client. Built lazily so no connection pool or dispatcher thread exists
     * until something actually makes a request.
     */
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * A client that shares [shared]'s connection pool and dispatcher but can override anything
     * else. Use this instead of constructing a fresh [OkHttpClient].
     */
    fun derived(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient = shared.newBuilder().apply(configure).build()
}
