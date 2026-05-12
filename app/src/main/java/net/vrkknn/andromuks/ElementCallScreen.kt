package net.vrkknn.andromuks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nav-route entry point for Element Call.
 *
 * In the new architecture the call UI is rendered by [CallOverlay], which lives permanently
 * above the NavHost. This screen simply starts the call and immediately pops itself so the
 * room timeline (or whatever screen launched the call) is visible behind the overlay.
 */
@Composable
fun ElementCallScreen(
    roomId: String,
    navController: NavController,
    appViewModel: AppViewModel,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    LaunchedEffect(roomId) {
        appViewModel.startCall(roomId)
        navController.popBackStack()
    }
}

// ---------------------------------------------------------------------------
// Shared helpers — internal so CallOverlay (same package) can access them.
// ---------------------------------------------------------------------------

internal fun buildElementCallUrl(
    baseUrl: String,
    roomId: String,
    userId: String,
    deviceId: String,
    homeserverUrl: String,
    perParticipantE2EE: Boolean,
    theme: String,
    widgetId: String,
    parentOrigin: String
): String {
    val normalizedBase = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val baseUri = Uri.parse(normalizedBase)
    val isEmbedded = baseUri.path?.contains("element-call-embedded") == true
    if (isEmbedded) {
        return baseUri.buildUpon()
            .appendQueryParameter("parentUrl", "$parentOrigin/")
            .appendQueryParameter("widgetId", widgetId)
            .appendQueryParameter("roomId", roomId)
            .appendQueryParameter("userId", userId)
            .appendQueryParameter("deviceId", deviceId)
            .appendQueryParameter("perParticipantE2EE", perParticipantE2EE.toString())
            .appendQueryParameter("baseUrl", homeserverUrl)
            .build()
            .toString()
    }

    val needsRoomSuffix = baseUri.lastPathSegment != "room"
    val callBase = if (needsRoomSuffix) baseUri.buildUpon().appendPath("room").build() else baseUri

    val params = Uri.Builder()
        .appendQueryParameter("roomId", roomId)
        .appendQueryParameter("theme", theme)
        .appendQueryParameter("userId", userId)
        .appendQueryParameter("deviceId", deviceId)
        .appendQueryParameter("perParticipantE2EE", perParticipantE2EE.toString())
        .appendQueryParameter("baseUrl", homeserverUrl)
        .appendQueryParameter("intent", "join_existing")
        .appendQueryParameter("hideHeader", "true")
        .appendQueryParameter("confineToRoom", "true")
        .appendQueryParameter("appPrompt", "false")
        .appendQueryParameter("lang", "en")
        .appendQueryParameter("fontScale", "1")
        .appendQueryParameter("rageshakeSubmitUrl", "https://element.io/bugreports/submit")
        .appendQueryParameter("preload", "false")
        .build()

    val hashParams = params.encodedQuery ?: ""
    val callBaseWithWidgetId = callBase.buildUpon()
        .appendQueryParameter("parentUrl", "$parentOrigin/")
        .appendQueryParameter("widgetId", widgetId)
        .build()
    return "${callBaseWithWidgetId}#?$hashParams"
}

internal fun deriveHomeserverBaseUrl(configuredBaseUrl: String, userId: String): String {
    if (configuredBaseUrl.isNotBlank()) return configuredBaseUrl
    val domain = userId.substringAfter(":", "").trim()
    if (domain.isBlank()) return ""
    return "https://$domain"
}

internal fun isRoomEncryptedFromState(events: JSONArray?): Boolean? {
    if (events == null) return null
    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        if (event.optString("type") == "m.room.encryption") {
            val algorithm = event.optJSONObject("content")?.optString("algorithm").orEmpty()
            if (algorithm.isNotBlank()) return true
        }
    }
    return false
}

internal fun isCallActiveInRoomState(events: JSONArray?): Boolean {
    if (events == null) return false
    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        if (event.optString("type") != "org.matrix.msc3401.call.member") continue
        val content = event.optJSONObject("content") ?: continue
        if (content.length() > 0) return true
    }
    return false
}

internal fun findActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> findActivity(context.baseContext)
    else -> null
}

// ---------------------------------------------------------------------------
// JS bridge — shared between CallOverlay and any future call UI.
// ---------------------------------------------------------------------------

internal class ElementCallJsBridge(
    private val webView: WebView,
    private val roomId: String,
    private val appViewModel: AppViewModel,
    private val onCallEnded: () -> Unit,
    private val onAlwaysOnScreen: (Boolean) -> Unit
) {
    private val syntheticDelayIds = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val screenOpenTimestamp = System.currentTimeMillis()

    @JavascriptInterface
    fun postMessage(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ElementCallJsBridge: received $message")
        val json = try { JSONObject(message) } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ElementCallJsBridge: invalid JSON: $message", e)
            return
        }

        val action = json.optString("action")
        val normalizedAction = action.lowercase()
        val widgetRequestId = json.opt("requestId")?.toString()?.takeIf { it.isNotBlank() }
        val data = json.opt("data")
        val requestData = when (data) {
            is JSONObject -> JSONObject(data.toString())
            else -> data
        }

        if (widgetRequestId == null) {
            android.util.Log.w("Andromuks", "ElementCallJsBridge: missing requestId for action=$action")
            return
        }

        if (requestData is JSONObject &&
            (normalizedAction.contains("send_event") || normalizedAction.contains("send_state") || normalizedAction.contains("set_state"))
        ) {
            val eventType = requestData.optString("type")
            if (eventType == "org.matrix.msc3401.call.member") {
                val deviceId = appViewModel.deviceId.ifBlank { appViewModel.getDeviceID().orEmpty() }
                if (!requestData.has("state_key") && deviceId.isNotBlank()) {
                    requestData.put("state_key", "_${appViewModel.currentUserId}_${deviceId}_m.call")
                }
                val content = requestData.optJSONObject("content") ?: JSONObject().also { requestData.put("content", it) }
                if (content.length() > 0 && !content.has("membershipID") && deviceId.isNotBlank()) {
                    content.put("membershipID", "${appViewModel.currentUserId}:${deviceId}")
                }
                if (content.length() > 0) appViewModel.setCallReadyForPip(true)
            }
        }

        if (normalizedAction.contains("update_delayed_event")) {
            // Respond immediately so Element Call never hits its ~11s timeout and retries.
            sendWidgetResponse(action, widgetRequestId, JSONObject())
            val delayId = (requestData as? JSONObject)?.optString("delay_id").orEmpty()
            if (delayId.isNotBlank() && !delayId.startsWith("andromuks-") && syntheticDelayIds.remove(delayId) != true) {
                val payload = ensureRoomId(requestData, roomId)
                appViewModel.sendWidgetCommand("update_delayed_event", payload) { /* fire-and-forget */ }
            }
            return
        }

        if (normalizedAction.contains("hangup") || normalizedAction.contains("leave_call") || normalizedAction.contains("call_ended")) {
            appViewModel.setCallReadyForPip(false)
            webView.post { onCallEnded() }
            sendWidgetResponse(action, widgetRequestId, JSONObject())
            return
        }

        if (normalizedAction.contains("always_on_screen")) {
            val enabled = (requestData as? JSONObject)?.optBoolean("value", false) ?: false
            webView.post { onAlwaysOnScreen(enabled) }
            sendWidgetResponse(action, widgetRequestId, JSONObject())
            return
        }

        if (normalizedAction.contains("read_events")) {
            val response = buildTimelineEventsResponse(requestData)
            sendWidgetResponse(action, widgetRequestId, response)
            return
        }

        val command = mapWidgetActionToCommand(action, requestData)
        if (command == null) {
            sendWidgetError(action, widgetRequestId, "Unsupported widget action: $action")
            return
        }

        val payload = if (requestData is JSONObject) {
            if (requestData.has("delay") && !requestData.has("delay_ms")) {
                val delayMs = requestData.optLong("delay", 0L)
                requestData.remove("delay")
                if (delayMs > 0L) requestData.put("delay_ms", delayMs)
            }
            if (command == "send_to_device") {
                if (!requestData.has("event_type")) {
                    val eventType = requestData.optString("type")
                    if (eventType.isNotBlank()) {
                        requestData.put("event_type", eventType)
                        requestData.remove("type")
                    }
                } else {
                    requestData.remove("type")
                }
            }
            ensureRoomId(requestData, roomId)
        } else {
            ensureRoomId(requestData, roomId)
        }

        appViewModel.sendWidgetCommand(command, payload) { result ->
            result.onSuccess { response ->
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "ElementCallJsBridge: response for $action -> $response")
                val normalizedResponse = normalizeWidgetResponse(action, response, requestData)
                sendWidgetResponse(action, widgetRequestId, normalizedResponse)
            }.onFailure { error ->
                if (BuildConfig.DEBUG) android.util.Log.w("Andromuks", "ElementCallJsBridge: error for $action -> ${error.message}")
                sendWidgetError(action, widgetRequestId, error.message ?: "Unknown error")
            }
        }
    }

    private fun mapWidgetActionToCommand(action: String, requestData: Any?): String? {
        val normalized = action.lowercase()
        if (normalized.contains("send_event") && requestData is JSONObject && requestData.has("state_key")) return "set_state"
        return when {
            normalized.contains("send_state") || normalized.contains("set_state") -> "set_state"
            normalized.contains("send_event") -> "send_event"
            normalized.contains("send_to_device") || normalized.contains("send.to_device") -> "send_to_device"
            normalized.contains("openid") -> "request_openid_token"
            normalized.contains("listen_to_device") || normalized.contains("receive_to_device") || normalized.contains("receive.to_device") -> "listen_to_device"
            normalized.contains("update_delayed_event") -> "update_delayed_event"
            normalized.contains("read_state") || normalized.contains("get_state") -> "get_room_state"
            normalized.contains("read_events") -> "get_room_state"
            else -> null
        }
    }

    private fun ensureRoomId(data: Any?, roomId: String): Any? {
        if (data is JSONObject) {
            if (!data.has("room_id")) data.put("room_id", roomId)
            return data
        }
        if (data == null) return JSONObject().put("room_id", roomId)
        return data
    }

    private fun sendWidgetResponse(action: String, widgetRequestId: String, response: Any?) {
        val payload = JSONObject()
        payload.put("action", action)
        payload.put("requestId", widgetRequestId)
        payload.put("response", response)
        postToWebView(payload)
    }

    private fun sendWidgetError(action: String, widgetRequestId: String, error: String) {
        val payload = JSONObject()
        payload.put("action", action)
        payload.put("requestId", widgetRequestId)
        payload.put("error", error)
        postToWebView(payload)
    }

    private fun normalizeWidgetResponse(action: String, response: Any?, requestData: Any?): Any? {
        val normalized = action.lowercase()
        if (normalized.contains("read_state") || normalized.contains("get_state")) {
            val rawEvents = when (response) {
                is JSONArray -> response
                is JSONObject -> response.optJSONArray("events") ?: JSONArray()
                is List<*> -> JSONArray(response)
                else -> JSONArray()
            }
            val filter = requestData as? JSONObject
            val typeFilter = filter?.optString("type")?.takeIf { it.isNotBlank() }
            val roomIdFilter = filter?.optJSONArray("room_ids")?.let { roomIds ->
                val ids = mutableSetOf<String>()
                for (i in 0 until roomIds.length()) roomIds.optString(i).takeIf { it.isNotBlank() }?.let { ids.add(it) }
                ids.takeIf { it.isNotEmpty() }
            }
            val stateKeyFilter = when (val stateKey = filter?.opt("state_key")) {
                is String -> stateKey
                is Boolean -> if (stateKey) null else ""
                else -> null
            }
            val eventsArray = JSONArray()
            for (i in 0 until rawEvents.length()) {
                val raw = rawEvents.optJSONObject(i) ?: continue
                val normalizedEvent = normalizeMatrixEvent(raw)
                if (typeFilter != null && normalizedEvent.optString("type") != typeFilter) continue
                if (roomIdFilter != null && normalizedEvent.optString("room_id") !in roomIdFilter) continue
                if (stateKeyFilter != null && normalizedEvent.optString("state_key") != stateKeyFilter) continue
                if (normalizedEvent.optString("type") == "org.matrix.msc3401.call.member") {
                    val content = normalizedEvent.optJSONObject("content")
                    if (content == null || content.length() == 0) {
                        val stateKey = normalizedEvent.optString("state_key")
                        val membershipParts = extractMembershipParts(stateKey)
                        if (membershipParts != null) {
                            val (eventUserId, eventDeviceId) = membershipParts
                            val ourDeviceId = appViewModel.deviceId.ifBlank { appViewModel.getDeviceID().orEmpty() }
                            if (eventUserId == appViewModel.currentUserId && eventDeviceId == ourDeviceId) {
                                val eventTs = normalizedEvent.optLong("origin_server_ts", 0L).takeIf { it > 0 }
                                    ?: normalizedEvent.optLong("timestamp", 0L)
                                if (eventTs > screenOpenTimestamp) {
                                    android.util.Log.d("Andromuks", "ElementCallJsBridge: own disconnect detected, closing")
                                    webView.post { onCallEnded() }
                                    continue
                                }
                            }
                        }
                    }
                }
                eventsArray.put(normalizedEvent)
            }
            return JSONObject().put("events", eventsArray)
        }
        if (normalized.contains("get_openid") || normalized.contains("openid")) {
            val obj = when (response) {
                is JSONObject -> response
                is Map<*, *> -> JSONObject(response)
                else -> JSONObject()
            }
            if (!obj.has("state")) obj.put("state", "allowed")
            return obj
        }
        if (normalized.contains("send_event") || normalized.contains("set_state") || normalized.contains("send_state")) {
            val requestJson = requestData as? JSONObject
            val hasDelay = requestJson?.has("delay_ms") == true || requestJson?.has("delay") == true
            if (hasDelay) {
                val delayId = when (response) {
                    is String -> response
                    is JSONObject -> response.optString("delay_id")
                    is Map<*, *> -> JSONObject(response).optString("delay_id")
                    else -> ""
                }
                if (delayId.isNotBlank()) return JSONObject().put("delay_id", delayId)
                val syntheticId = "andromuks-${System.currentTimeMillis()}"
                syntheticDelayIds[syntheticId] = true
                return JSONObject().put("delay_id", syntheticId)
            }
        }
        if (normalized.contains("send_event")) {
            return when (response) {
                is String -> JSONObject().put("event_id", response)
                is JSONObject -> {
                    if (!response.has("event_id")) {
                        response.optString("eventId").takeIf { it.isNotBlank() }?.let { response.put("event_id", it) }
                    }
                    response
                }
                is Map<*, *> -> {
                    val obj = JSONObject(response)
                    if (!obj.has("event_id")) obj.optString("eventId").takeIf { it.isNotBlank() }?.let { obj.put("event_id", it) }
                    obj
                }
                else -> response
            }
        }
        return response
    }

    private fun normalizeMatrixEvent(raw: JSONObject): JSONObject {
        val event = JSONObject()
        raw.optString("event_id").takeIf { it.isNotBlank() }?.let { event.put("event_id", it) }
        val decryptedType = raw.optString("decrypted_type").takeIf { it.isNotBlank() }
        if (decryptedType != null) event.put("type", decryptedType)
        else raw.optString("type").takeIf { it.isNotBlank() }?.let { event.put("type", it) }
        raw.optString("sender").takeIf { it.isNotBlank() }?.let { event.put("sender", it) }
        if (raw.has("state_key")) event.put("state_key", raw.optString("state_key"))
        raw.optString("room_id").takeIf { it.isNotBlank() }?.let { event.put("room_id", it) }
        val originTs = if (raw.has("origin_server_ts")) raw.optLong("origin_server_ts", 0L) else raw.optLong("timestamp", 0L)
        if (originTs > 0) event.put("origin_server_ts", originTs)
        if (decryptedType != null && raw.has("decrypted")) event.put("content", raw.opt("decrypted"))
        else if (raw.has("content")) event.put("content", raw.opt("content"))
        val contentObject = event.optJSONObject("content")
        if (contentObject != null && contentObject.length() == 0 && event.optString("type") != "org.matrix.msc3401.call.member") {
            val prevContent = raw.optJSONObject("unsigned")?.optJSONObject("prev_content")
            if (prevContent != null && prevContent.length() > 0) event.put("content", prevContent)
        }
        if (event.optString("type") == "org.matrix.msc3401.call.member") {
            val content = event.optJSONObject("content")
            if (content != null && content.length() > 0) {
                val stateKey = event.optString("state_key")
                if (content.optString("device_id").isNullOrBlank()) {
                    extractMembershipParts(stateKey)?.second?.let { content.put("device_id", it) }
                }
                if (!content.has("membershipID")) {
                    val membership = extractMembershipParts(stateKey)
                    val userId = membership?.first ?: event.optString("sender")
                    val deviceId = membership?.second ?: content.optString("device_id")
                    if (!userId.isNullOrBlank() && !deviceId.isNullOrBlank()) content.put("membershipID", "$userId:$deviceId")
                }
            }
        }
        if (raw.has("unsigned")) event.put("unsigned", raw.opt("unsigned"))
        return event
    }

    private fun buildTimelineEventsResponse(requestData: Any?): JSONObject {
        val filter = requestData as? JSONObject
        val roomId = filter?.optString("room_id")?.takeIf { it.isNotBlank() }
        val roomIds = filter?.optJSONArray("room_ids")?.let { ids ->
            val list = mutableListOf<String>()
            for (i in 0 until ids.length()) ids.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
            list
        } ?: emptyList()
        val typeFilter = filter?.optString("type")?.takeIf { it.isNotBlank() }
        val limit = filter?.optInt("limit", 0)?.takeIf { it > 0 } ?: 0
        val targets = when {
            roomId != null -> listOf(roomId)
            roomIds.isNotEmpty() -> roomIds
            else -> emptyList()
        }
        val eventsArray = JSONArray()
        for (targetRoomId in targets) {
            val cached = RoomTimelineCache.getCachedEvents(targetRoomId).orEmpty()
            for (event in cached) {
                if (typeFilter != null && event.type != typeFilter) continue
                eventsArray.put(timelineEventToMatrixEvent(event))
            }
        }
        if (limit > 0 && eventsArray.length() > limit) {
            val trimmed = JSONArray()
            val start = eventsArray.length() - limit
            for (i in start until eventsArray.length()) trimmed.put(eventsArray.get(i))
            return JSONObject().put("events", trimmed)
        }
        return JSONObject().put("events", eventsArray)
    }

    private fun timelineEventToMatrixEvent(event: TimelineEvent): JSONObject {
        val obj = JSONObject()
        if (event.eventId.isNotBlank()) obj.put("event_id", event.eventId)
        if (event.type.isNotBlank()) obj.put("type", event.type)
        if (event.sender.isNotBlank()) obj.put("sender", event.sender)
        if (event.roomId.isNotBlank()) obj.put("room_id", event.roomId)
        if (event.timestamp > 0) obj.put("origin_server_ts", event.timestamp)
        event.stateKey?.let { obj.put("state_key", it) }
        if (event.decryptedType != null && event.decrypted != null) {
            obj.put("type", event.decryptedType)
            obj.put("content", event.decrypted)
        } else if (event.content != null) {
            obj.put("content", event.content)
        }
        event.unsigned?.let { obj.put("unsigned", it) }
        return obj
    }

    private fun postToWebView(payload: JSONObject) {
        val jsPayload = JSONObject.quote(payload.toString())
        webView.post {
            webView.evaluateJavascript("window.__andromuksWidgetHost.onNativeResponse($jsPayload);", null)
        }
    }

    private fun extractMembershipParts(stateKey: String?): Pair<String, String>? {
        if (stateKey.isNullOrBlank() || !stateKey.startsWith("_") || !stateKey.endsWith("_m.call")) return null
        val trimmed = stateKey.removePrefix("_").removeSuffix("_m.call")
        val splitIndex = trimmed.lastIndexOf('_')
        if (splitIndex <= 0 || splitIndex >= trimmed.length - 1) return null
        val userId = trimmed.substring(0, splitIndex)
        val deviceId = trimmed.substring(splitIndex + 1)
        if (userId.isBlank() || deviceId.isBlank()) return null
        return userId to deviceId
    }
}
