package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the Go HTTP sidecar at <homeserver_url>/_gomuks/sidecar/ for cases where the
 * persistent WebSocket is not open (sidecar mode while backgrounded). Used by
 * NotificationReplyReceiver / NotificationMarkReadReceiver.
 *
 * Auth is the existing gomuks_auth token stored in SharedPreferences — the sidecar
 * shares gomuks's token_key, so the same cookie validates on both sides.
 */
object SidecarApi {
    private const val TAG = "SidecarApi"
    private const val PATH_SEND_MSG = "/_gomuks/sidecar/send_msg"
    private const val PATH_MARK_READ = "/_gomuks/sidecar/mark_read"
    private const val PATH_HEALTH = "/_gomuks/sidecar/healthz"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    data class Credentials(val homeserverUrl: String, val authToken: String) {
        fun isValid(): Boolean = homeserverUrl.isNotBlank() && authToken.isNotBlank()
    }

    /** Reads homeserver_url and gomuks_auth_token from the same SharedPreferences the rest of the app uses. */
    fun readCredentials(context: Context): Credentials {
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        return Credentials(
            homeserverUrl = prefs.getString("homeserver_url", "") ?: "",
            authToken = prefs.getString("gomuks_auth_token", "") ?: ""
        )
    }

    sealed class HealthResult {
        object Ok : HealthResult()
        /** Sidecar reachable but reported it can't talk to gomuks. */
        object SidecarUnhealthy : HealthResult()
        /** HTTP-level error (404 if path not routed, 401 if auth wrong, etc.). */
        data class HttpError(val code: Int, val message: String) : HealthResult()
        /** Network/IO error — sidecar not deployed, DNS, TLS, timeout, etc. */
        data class NetworkError(val message: String) : HealthResult()
        /** No homeserver_url stored; user hasn't logged in yet. */
        object NotConfigured : HealthResult()
    }

    /**
     * Probe the sidecar's healthz endpoint. Blocking — call off the main thread.
     * Uses a short timeout so the settings-screen UX feels responsive.
     */
    fun probeHealth(creds: Credentials): HealthResult {
        if (creds.homeserverUrl.isBlank()) return HealthResult.NotConfigured
        val url = creds.homeserverUrl.trimEnd('/') + PATH_HEALTH
        val builder = Request.Builder().url(url).get()
        if (creds.authToken.isNotBlank()) {
            builder.header("Cookie", "gomuks_auth=${creds.authToken}")
        }
        val shortClient = client.newBuilder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        return try {
            shortClient.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> HealthResult.Ok
                    resp.code == 503 -> HealthResult.SidecarUnhealthy
                    else -> HealthResult.HttpError(resp.code, resp.message)
                }
            }
        } catch (e: IOException) {
            HealthResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Blocking — must be called off the main thread. Returns true on 2xx. */
    fun sendMessage(creds: Credentials, roomId: String, text: String): Boolean {
        if (!creds.isValid()) {
            Log.e(TAG, "sendMessage: missing credentials")
            return false
        }
        val body = JSONObject().apply {
            put("room_id", roomId)
            put("text", text)
        }
        return post(creds, PATH_SEND_MSG, body)
    }

    /** Blocking — must be called off the main thread. Returns true on 2xx. */
    fun markRead(creds: Credentials, roomId: String, eventId: String): Boolean {
        if (!creds.isValid()) {
            Log.e(TAG, "markRead: missing credentials")
            return false
        }
        val body = JSONObject().apply {
            put("room_id", roomId)
            put("event_id", eventId)
            put("receipt_type", "m.read")
        }
        return post(creds, PATH_MARK_READ, body)
    }

    private fun post(creds: Credentials, path: String, body: JSONObject): Boolean {
        val url = creds.homeserverUrl.trimEnd('/') + path
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Cookie", "gomuks_auth=${creds.authToken}")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val ok = resp.isSuccessful
                if (!ok) {
                    Log.w(TAG, "POST $path failed: HTTP ${resp.code} ${resp.message}")
                } else if (BuildConfig.DEBUG) {
                    Log.d(TAG, "POST $path -> ${resp.code}")
                }
                ok
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST $path threw IOException", e)
            false
        }
    }
}
