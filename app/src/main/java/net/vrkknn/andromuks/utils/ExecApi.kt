package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Executes one-off gomuks RPC commands over HTTP for cases where the persistent WebSocket is not
 * open (battery-saver mode while backgrounded). Used by NotificationReplyReceiver /
 * NotificationMarkReadReceiver for fire-and-forget actions, and by ExecCommandCoordinator for
 * commands whose response must be plumbed back through the normal WebSocket dispatcher.
 *
 * Transport is the official gomuks endpoint POST <homeserver_url>/_gomuks/exec/{command}: the raw
 * JSON request body becomes the command's `data` field — identical to the WebSocket frame's `data`
 * — and the command name is the path segment. The HTTP response body is the command's result, i.e.
 * byte-identical to the `data` field of the WebSocket `response` frame. Responses: 200 success,
 * 418 command error, 401 missing auth cookie.
 *
 * Auth is the existing gomuks_auth token stored in SharedPreferences.
 */
object ExecApi {
    private const val TAG = "ExecApi"
    private const val PATH_EXEC = "/_gomuks/exec/"
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

    /**
     * Outcome of an /exec call. [Success.data] is the parsed response body using the same
     * [org.json] types the WebSocket `response` path produces (`JSONObject`, `JSONArray`, `Boolean`,
     * `String`, `Number`, or `JSONObject.NULL`), so it can be handed straight to
     * `AppViewModel.handleResponse`. The error variants map to `AppViewModel.handleError`.
     */
    sealed class ExecResult {
        data class Success(val data: Any) : ExecResult()
        /** Command ran but returned an error (HTTP 418). */
        data class CommandError(val message: String) : ExecResult()
        /** Auth cookie missing or rejected (HTTP 401). */
        object AuthMissing : ExecResult()
        /** Any other non-2xx HTTP status. */
        data class HttpError(val code: Int, val message: String) : ExecResult()
        /** Network/IO failure — host unreachable, timeout, TLS, etc. */
        data class NetworkError(val message: String) : ExecResult()
    }

    /** Reads homeserver_url and gomuks_auth_token from the same SharedPreferences the rest of the app uses. */
    fun readCredentials(context: Context): Credentials {
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        return Credentials(
            homeserverUrl = prefs.getString("homeserver_url", "") ?: "",
            authToken = prefs.getString("gomuks_auth_token", "") ?: ""
        )
    }

    /**
     * Execute [command] with [body] as its `data`, parsing the response. Blocking — must be called
     * off the main thread. This is the generic transport that every command goes through.
     */
    fun execRaw(creds: Credentials, command: String, body: JSONObject): ExecResult {
        if (!creds.isValid()) {
            Log.e(TAG, "execRaw($command): missing credentials")
            return ExecResult.AuthMissing
        }
        val url = creds.homeserverUrl.trimEnd('/') + PATH_EXEC + command
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Cookie", "gomuks_auth=${creds.authToken}")
            .header("User-Agent", getUserAgent())
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val payload = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        if (BuildConfig.DEBUG) Log.d(TAG, "exec $command -> ${resp.code}")
                        // The body is the command's result, i.e. the WS frame's `data` value.
                        // Parse with JSONTokener so the type matches what jsonObject.opt("data")
                        // yields on the WebSocket path. An empty body means "no data".
                        val data: Any = if (payload.isBlank()) JSONObject() else JSONTokener(payload).nextValue()
                        ExecResult.Success(data)
                    }
                    resp.code == 418 -> {
                        Log.w(TAG, "exec $command returned a command error: $payload")
                        ExecResult.CommandError(payload.ifBlank { "command error" })
                    }
                    resp.code == 401 -> {
                        Log.w(TAG, "exec $command: auth cookie missing/rejected")
                        ExecResult.AuthMissing
                    }
                    else -> {
                        Log.w(TAG, "exec $command failed: HTTP ${resp.code} ${resp.message}")
                        ExecResult.HttpError(resp.code, resp.message)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "exec $command threw IOException", e)
            ExecResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "exec $command: could not parse response body", e)
            ExecResult.NetworkError("malformed response: ${e.message}")
        }
    }

    /** Fire-and-forget send. Blocking — must be called off the main thread. Returns true on success. */
    fun sendMessage(creds: Credentials, roomId: String, text: String): Boolean {
        val body = JSONObject().apply {
            put("room_id", roomId)
            put("text", text)
            put("mentions", JSONObject().apply {
                put("user_ids", org.json.JSONArray())
                put("room", false)
            })
        }
        return execRaw(creds, "send_message", body) is ExecResult.Success
    }

    /** Fire-and-forget mark-read. Blocking — must be called off the main thread. Returns true on success. */
    fun markRead(creds: Credentials, roomId: String, eventId: String): Boolean {
        val body = JSONObject().apply {
            put("room_id", roomId)
            put("event_id", eventId)
            put("receipt_type", "m.read")
        }
        return execRaw(creds, "mark_read", body) is ExecResult.Success
    }
}
