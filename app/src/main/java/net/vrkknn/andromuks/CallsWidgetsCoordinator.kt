package net.vrkknn.andromuks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.vrkknn.andromuks.utils.getUserAgent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Element Call `.well-known` resolution, call UI state, and widget WebSocket commands — [AppViewModel].
 */
internal class CallsWidgetsCoordinator(private val vm: AppViewModel) {

    fun refreshElementCallBaseUrlFromWellKnown() = with(vm) {
        val homeserver = realMatrixHomeserverUrl.trim()
        if (homeserver.isBlank()) return@with
        val wellKnownUrl = homeserver.trimEnd('/') + "/.well-known/matrix/client"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().build()
                val request = Request.Builder()
                    .url(wellKnownUrl)
                    .get()
                    .header("User-Agent", getUserAgent())
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.w(
                                "Andromuks",
                                "AppViewModel: .well-known fetch failed ${response.code}",
                            )
                        }
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val json = JSONObject(body)
                    val rtcFoci = json.optJSONArray("org.matrix.msc4143.rtc_foci")
                    var derivedBaseUrl: String? = null
                    if (rtcFoci != null) {
                        for (i in 0 until rtcFoci.length()) {
                            val entry = rtcFoci.optJSONObject(i) ?: continue
                            if (entry.optString("type") != "livekit") continue
                            val serviceUrl = entry.optString("livekit_service_url").trim()
                            if (serviceUrl.isBlank()) continue
                            try {
                                val uri = java.net.URI(serviceUrl)
                                val scheme = uri.scheme ?: "https"
                                val host = uri.host ?: continue
                                val port = uri.port
                                val origin = if (port == -1) {
                                    "$scheme://$host"
                                } else {
                                    "$scheme://$host:$port"
                                }
                                derivedBaseUrl = origin.trimEnd('/') + "/room"
                                break
                            } catch (_: Exception) {
                                // Ignore invalid URLs and continue scanning.
                            }
                        }
                    }
                    if (!derivedBaseUrl.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            wellKnownElementCallBaseUrl = derivedBaseUrl
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d(
                                    "Andromuks",
                                    "AppViewModel: Resolved Element Call base URL from .well-known: $derivedBaseUrl",
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "AppViewModel: .well-known fetch failed", e)
                }
            }
        }
    }

    fun setCallActive(active: Boolean) = with(vm) {
        callActiveInternal = active
    }

    fun isCallActive(): Boolean = vm.callActiveInternal

    fun setCallReadyForPip(ready: Boolean) = with(vm) {
        callReadyForPipInternal = ready
    }

    fun isCallReadyForPip(): Boolean = vm.callReadyForPipInternal

    fun sendWidgetCommand(command: String, data: Any?, onResult: (Result<Any?>) -> Unit) = with(vm) {
        val requestId = requestIdCounter++
        val deferred = CompletableDeferred<Any?>()
        widgetCommandRequests[requestId] = deferred

        val result = sendRawWebSocketCommand(command, requestId, data)
        if (result != WebSocketResult.SUCCESS) {
            widgetCommandRequests.remove(requestId)
            onResult(Result.failure(IllegalStateException("WebSocket not connected")))
            return@with
        }

        viewModelScope.launch {
            val response = withTimeoutOrNull(30_000L) { deferred.await() }
            if (response == null) {
                widgetCommandRequests.remove(requestId)
                onResult(Result.failure(java.util.concurrent.TimeoutException("Widget command timeout")))
            } else {
                onResult(Result.success(response))
            }
        }
        Unit
    }
}
