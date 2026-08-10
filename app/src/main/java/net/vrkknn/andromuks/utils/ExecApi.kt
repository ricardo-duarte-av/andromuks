package net.vrkknn.andromuks.utils

import android.content.Context
import android.util.Log
import net.vrkknn.andromuks.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.UUID
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
 * Auth is the existing gomuks_auth token stored in SharedPreferences, with an HTTP-basic fallback
 * (see [Credentials.basicAuthProvider]) for the case where that token has expired in a background
 * context that cannot run the interactive re-auth flow.
 *
 * ## Idempotency / de-duplication (optional `txn_id`)
 *
 * gomuks' `/exec` accepts an optional `?txn_id=<id>&start_ts=<clientMillis>` query pair (server
 * commit "server: add optional transaction ID to /exec request"). When present, the server keeps a
 * 5-minute execution buffer keyed by `txn_id`: the first request runs the command; any *retry*
 * carrying the same `txn_id` within that window blocks on the in-flight call and returns the first
 * attempt's cached result instead of re-executing. This is what makes retrying a *non-idempotent*
 * command such as `send_message` safe — a lost HTTP response no longer risks a double-send.
 *
 * We only attach the envelope to commands we retry ([sendMessage], [markRead]); read-only or
 * response-plumbed commands ([ExecCommandCoordinator]) omit it and are unaffected. Omitting the
 * params is fully backward compatible: an older server (or a call with no [Idempotency]) behaves
 * exactly as before. See docs/EXEC_ENDPOINT.md for the full contract, including the clock-skew
 * rules that back `start_ts`.
 *
 * ## HTTP-basic fallback
 *
 * gomuks' `AuthMiddleware` falls back to HTTP basic auth when the session cookie is absent or
 * rejected (server commit `fdcb9b0c`, "server: allow using any endpoint with basic auth"). That
 * matters here because `/exec` callers are background components — a [android.content.BroadcastReceiver]
 * handling a notification action, a [androidx.work.Worker] — which cannot run the interactive
 * re-auth flow. Before, an expired token silently lost the user's reply; now the same call
 * re-issues itself under the stored login credentials.
 *
 * Two rules keep this from being a downgrade:
 *  1. **Cookie first, basic only on 401.** The token is revocable and scoped; the password is
 *     neither. Basic is a fallback, never the primary credential.
 *  2. **HTTPS only.** [Credentials.isSecureTransport] gates it, so a plaintext homeserver URL can
 *     never put the account password on the wire. A cleartext deployment keeps the old behaviour
 *     (401 → give up), which is the correct trade.
 */
object ExecApi {
    private const val TAG = "ExecApi"
    private const val PATH_EXEC = "/_gomuks/exec/"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Errcode returned (HTTP 400) when the device clock is too far ahead of the server. */
    private const val ERRCODE_TIME_DESYNC = "FI.MAU.GOMUKS.TIME_DESYNC"

    /** Errcode returned (HTTP 400) when the device clock is too far behind / the buffer entry expired. */
    private const val ERRCODE_REQUEST_EXPIRED = "FI.MAU.GOMUKS.REQUEST_EXPIRED"

    /** Max attempts for an idempotent retry loop (1 initial + retries). */
    private const val MAX_RETRY_ATTEMPTS = 3

    /** Backoff between idempotent retry attempts. */
    private const val RETRY_BACKOFF_MS = 750L

    private val client: OkHttpClient by lazy {
        HttpClientProvider.derived {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
            writeTimeout(20, TimeUnit.SECONDS)
        }
    }

    /**
     * Username + password for the HTTP-basic fallback. Never persisted by this object, and
     * [toString] is redacted so the password cannot reach a log line by accident.
     */
    data class BasicAuth(val username: String, val password: String) {
        override fun toString(): String = "BasicAuth($username, ***)"
    }

    /**
     * @param basicAuthProvider Lazily resolves the stored login credentials for the basic-auth
     *   fallback, or null when none are stored. Deliberately a *provider* rather than a value: it
     *   performs a Keystore decrypt (~20 ms of disk I/O + crypto) and [readCredentials] is called on
     *   the main thread by `RpcResilienceCoordinator.dispatchOverExec`. Invoked at most once per
     *   call, only after a 401, always from the blocking (off-main) portion of [execRaw].
     */
    data class Credentials(val homeserverUrl: String, val authToken: String, val basicAuthProvider: (() -> BasicAuth?)? = null) {
        /**
         * True when we have *some* way to authenticate. Callers use this to decide whether an
         * `/exec` attempt is worth making at all (`RpcResilienceCoordinator` parks the request when
         * it is false), so stored login credentials count even with no token.
         */
        fun isValid(): Boolean = homeserverUrl.isNotBlank() && (authToken.isNotBlank() || basicAuthProvider != null)

        /** Basic auth is only ever attached over TLS — see the class-level "HTTP-basic fallback" note. */
        val isSecureTransport: Boolean get() = homeserverUrl.startsWith("https://", ignoreCase = true)
    }

    /**
     * De-duplication envelope for a retried `/exec` call. [txnId] must stay identical across every
     * retry of one logical action so the server collapses them; [startTs] is the device wall-clock
     * (millis) at the *first* attempt and must also be reused unchanged, otherwise a slow retry
     * could age past the server's expiry window. Build one per logical action with [new].
     */
    data class Idempotency(val txnId: String, val startTs: Long) {
        companion object {
            fun new(): Idempotency = Idempotency(UUID.randomUUID().toString(), System.currentTimeMillis())
        }
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

        /**
         * The idempotency envelope was rejected on clock-skew grounds (HTTP 400,
         * [ERRCODE_TIME_DESYNC] or [ERRCODE_REQUEST_EXPIRED]). The command did *not* run. Retrying
         * with the same envelope is pointless; the caller should fall back to a plain (no-`txn_id`)
         * call so the action still goes through.
         */
        data class IdempotencyRejected(val errcode: String, val message: String) : ExecResult()

        /** Any other non-2xx HTTP status. */
        data class HttpError(val code: Int, val message: String) : ExecResult()

        /** Network/IO failure — host unreachable, timeout, TLS, etc. */
        data class NetworkError(val message: String) : ExecResult()
    }

    /**
     * Reads homeserver_url and gomuks_auth_token from the same SharedPreferences the rest of the app
     * uses. Safe to call on the main thread: the token comes from [CredentialStore]'s in-memory
     * cache, and the basic-auth credentials are only *probed* for presence
     * ([CredentialStore.hasCredentials] is a plain SharedPreferences read) — the Keystore decrypt is
     * deferred into [Credentials.basicAuthProvider].
     */
    fun readCredentials(context: Context): Credentials {
        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val hasStoredLogin = CredentialStore.hasCredentials(prefs)
        return Credentials(
            homeserverUrl = prefs.getString("homeserver_url", "") ?: "",
            authToken = CredentialStore.getAuthToken(prefs),
            basicAuthProvider = if (hasStoredLogin) {
                {
                    CredentialStore.loadCredentials(prefs)
                        ?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }
                        ?.let { BasicAuth(it.username, it.password) }
                }
            } else {
                null
            },
        )
    }

    /**
     * Execute [command] with [body] as its `data`, parsing the response. Blocking — must be called
     * off the main thread. This is the generic transport that every command goes through.
     *
     * @param idempotency When non-null, attaches `?txn_id&start_ts` so the server de-duplicates
     *   retries carrying the same envelope. Only pass it for commands you may retry — see the
     *   class-level "Idempotency" note. A [ExecResult.IdempotencyRejected] result means the envelope
     *   was refused (clock skew); re-issue without one.
     */
    fun execRaw(creds: Credentials, command: String, body: JSONObject, idempotency: Idempotency? = null): ExecResult {
        if (!creds.isValid()) {
            Log.e(TAG, "execRaw($command): missing credentials")
            return ExecResult.AuthMissing
        }
        val urlBuilder = (creds.homeserverUrl.trimEnd('/') + PATH_EXEC + command).toHttpUrl().newBuilder()
        if (idempotency != null) {
            urlBuilder.addQueryParameter("txn_id", idempotency.txnId)
            urlBuilder.addQueryParameter("start_ts", idempotency.startTs.toString())
        }
        val url = urlBuilder.build()

        // Cookie first, then the basic-auth fallback. A 401 means the command did not run, so the
        // second attempt cannot double-execute — and reusing the same [idempotency] envelope keeps
        // that guarantee even if the first attempt's response was merely lost in transit.
        val cookieAttempt = creds.authToken.takeIf { it.isNotBlank() }
            ?.let { AuthAttempt("cookie", "Cookie", "gomuks_auth=$it") }
        var result: ExecResult = ExecResult.AuthMissing
        if (cookieAttempt != null) {
            result = performExec(url, command, body, cookieAttempt)
            if (result !is ExecResult.AuthMissing) return result
        }
        val basicAttempt = resolveBasicAttempt(creds, command) ?: return result
        if (cookieAttempt != null) {
            Log.w(TAG, "exec $command: cookie auth rejected, retrying with HTTP basic auth")
        }
        return performExec(url, command, body, basicAttempt)
    }

    /**
     * One authenticated attempt at a request: a header pair plus a label for logging.
     *
     * [toString] is redacted on purpose — [headerValue] carries either the session token or the
     * base64 account password, and a data class's generated `toString` would put it in any log line
     * that interpolated the object.
     */
    private data class AuthAttempt(val label: String, val headerName: String, val headerValue: String) {
        override fun toString(): String = "AuthAttempt($label)"
    }

    /**
     * Builds the basic-auth attempt, or null when unavailable. Performs the Keystore decrypt, so it
     * only runs on the 401 path — never on the happy path and never on the main thread.
     */
    private fun resolveBasicAttempt(creds: Credentials, command: String): AuthAttempt? {
        val provider = creds.basicAuthProvider ?: return null
        if (!creds.isSecureTransport) {
            Log.w(TAG, "exec $command: refusing HTTP basic fallback over a non-HTTPS homeserver URL")
            return null
        }
        val basic = provider() ?: return null
        return AuthAttempt("basic", "Authorization", okhttp3.Credentials.basic(basic.username, basic.password))
    }

    /** Issues one authenticated `/exec` request and maps the HTTP outcome onto an [ExecResult]. */
    private fun performExec(url: okhttp3.HttpUrl, command: String, body: JSONObject, auth: AuthAttempt): ExecResult {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header(auth.headerName, auth.headerValue)
            .header("User-Agent", getUserAgent())
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val payload = resp.body.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        if (BuildConfig.DEBUG) Log.d(TAG, "exec $command (${auth.label}) -> ${resp.code}")
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
                        Log.w(TAG, "exec $command: ${auth.label} auth missing/rejected")
                        ExecResult.AuthMissing
                    }

                    // A clock-skew rejection of the idempotency envelope: the command did NOT run.
                    // Surface it distinctly so the retry helper can fall back to a plain call.
                    resp.code == 400 && parseErrcode(payload)?.let {
                        it == ERRCODE_TIME_DESYNC || it == ERRCODE_REQUEST_EXPIRED
                    } == true -> {
                        val errcode = parseErrcode(payload).orEmpty()
                        Log.w(TAG, "exec $command idempotency rejected ($errcode): $payload")
                        ExecResult.IdempotencyRejected(errcode, payload)
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

    /**
     * Run a read-only [command] and hand back its response object, or null on any failure.
     *
     * This is the facade for callers that consume the result **directly** rather than plumbing it
     * through `AppViewModel.handleResponse` — notification enrichment, background workers, anything
     * running without a ViewModel. Use [ExecCommandCoordinator] instead when the response needs to
     * land in the app's caches via the normal dispatcher.
     *
     * No idempotency envelope: this is for reads, where re-running costs a round-trip and nothing
     * else. Blocking — must be called off the main thread.
     */
    fun callObject(creds: Credentials, command: String, body: JSONObject): JSONObject? =
        (execRaw(creds, command, body) as? ExecResult.Success)?.data as? JSONObject

    /**
     * [callObject] for the commands whose response is a bare array rather than an object.
     *
     * `get_specific_room_state` is the one that matters today: it answers with a JSONArray of state
     * events, which is how the room widget resolves sender display names and avatars without a
     * ViewModel (`AppViewModel.handleRoomSpecificStateResponse` does the same cast on the WS side).
     * `paginate` can also answer with a bare array instead of an `{events: […]}` envelope, so
     * callers of that command should try both shapes.
     */
    fun callArray(creds: Credentials, command: String, body: JSONObject): org.json.JSONArray? =
        (execRaw(creds, command, body) as? ExecResult.Success)?.data as? org.json.JSONArray

    /** Fire-and-forget send. Blocking — must be called off the main thread. Returns true on success. */
    fun sendMessage(creds: Credentials, roomId: String, text: String): Boolean {
        val body = JSONObject().apply {
            put("room_id", roomId)
            put("text", text)
            put(
                "mentions",
                JSONObject().apply {
                    put("user_ids", org.json.JSONArray())
                    put("room", false)
                },
            )
        }
        // send_message is non-idempotent, so retry under a stable txn_id: a lost response no longer
        // risks a double-send — the server collapses the retry onto the first attempt's result.
        return execWithIdempotentRetry(creds, "send_message", body) is ExecResult.Success
    }

    /** Fire-and-forget mark-read. Blocking — must be called off the main thread. Returns true on success. */
    fun markRead(creds: Credentials, roomId: String, eventId: String): Boolean {
        val body = JSONObject().apply {
            put("room_id", roomId)
            put("event_id", eventId)
            put("receipt_type", "m.read")
        }
        // mark_read is naturally idempotent; the envelope just makes the retry a cheap server-side
        // no-op instead of a second real receipt write.
        return execWithIdempotentRetry(creds, "mark_read", body) is ExecResult.Success
    }

    /**
     * Mute a room by putting an empty-actions room-scoped push rule, the same shape
     * `PushRulesCoordinator.setRoomNotificationLevel(MUTE)` sends over the WebSocket. Blocking —
     * must be called off the main thread. Returns true on success.
     */
    fun muteRoom(creds: Credentials, roomId: String): Boolean {
        val body = JSONObject().apply {
            put("kind", "room")
            put("rule_id", roomId)
            put("action", "put")
            put(
                "new_content",
                JSONObject().apply {
                    // Empty actions is what gomuks reads as "muted"; conditions/pattern are
                    // required-but-empty for a room-scoped rule.
                    put("actions", org.json.JSONArray())
                    put("conditions", org.json.JSONArray())
                    put("pattern", "")
                },
            )
        }
        // Naturally idempotent (the rule ends up in the same state either way); the envelope just
        // makes a retry a cheap server-side no-op instead of a second rule write.
        return execWithIdempotentRetry(creds, "update_push_rule", body) is ExecResult.Success
    }

    /**
     * Run [command] with a de-duplication envelope, retrying transient network failures under the
     * *same* [Idempotency] so a landed-but-unacknowledged attempt is collapsed by the server rather
     * than re-executed. Terminal outcomes (success, command error, auth) return immediately. A
     * clock-skew [ExecResult.IdempotencyRejected] falls back to a single plain call so the action
     * still goes through on a skewed device. Blocking — call off the main thread.
     */
    private fun execWithIdempotentRetry(creds: Credentials, command: String, body: JSONObject): ExecResult {
        val envelope = Idempotency.new()
        var last: ExecResult = ExecResult.NetworkError("no attempt made")
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            when (val result = execRaw(creds, command, body, envelope)) {
                is ExecResult.NetworkError -> {
                    last = result
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "exec $command network error, retrying (attempt ${attempt + 1})")
                        try {
                            Thread.sleep(RETRY_BACKOFF_MS)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return result
                        }
                    }
                }

                is ExecResult.IdempotencyRejected -> {
                    // Clock skew: the envelope is unusable. The command never ran, so a plain call
                    // is safe and gets the message/receipt out on a mis-clocked device.
                    Log.w(TAG, "exec $command idempotency rejected (${result.errcode}); retrying without txn_id")
                    return execRaw(creds, command, body, idempotency = null)
                }

                else -> return result
            }
        }
        return last
    }

    /** Pulls the `errcode` field out of a gomuks/mautrix `RespError` JSON body, or null if absent. */
    private fun parseErrcode(payload: String): String? = try {
        (JSONTokener(payload).nextValue() as? JSONObject)?.optString("errcode")?.ifBlank { null }
    } catch (e: org.json.JSONException) {
        null
    }
}
