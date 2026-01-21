package net.vrkknn.andromuks

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.content.pm.PackageManager
import android.util.Rational
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.webkit.WebViewAssetLoader
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementCallScreen(
    roomId: String,
    navController: NavController,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pendingWebPermissionRequest = remember { mutableStateOf<PermissionRequest?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        pendingWebPermissionRequest.value?.let { request ->
            if (granted) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
        }
        pendingWebPermissionRequest.value = null
    }

    val isEncrypted = appViewModel.currentRoomState?.isEncrypted
        ?: isRoomEncryptedFromState(appViewModel.getRoomState(roomId))
        ?: false
    val effectiveDeviceId = appViewModel.deviceId.ifBlank {
        appViewModel.getDeviceID().orEmpty()
    }
    val configuredCallBaseUrl = appViewModel.elementCallBaseUrl.trim()
    val wellKnownCallBaseUrl = appViewModel.wellKnownElementCallBaseUrl.trim()
    val callBaseUrl = when {
        configuredCallBaseUrl.isNotBlank() -> configuredCallBaseUrl
        wellKnownCallBaseUrl.isNotBlank() -> wellKnownCallBaseUrl
        else -> "https://call.element.io/"
    }
    val homeserverBaseUrl = deriveHomeserverBaseUrl(
        appViewModel.realMatrixHomeserverUrl,
        appViewModel.currentUserId
    )
    val theme = if (isSystemInDarkTheme()) "dark" else "light"
    val isLoading = remember { mutableStateOf(true) }
    val loadError = remember { mutableStateOf<String?>(null) }
    val callWebView = remember { mutableStateOf<WebView?>(null) }
    val lastLoadedUrl = remember { mutableStateOf<String?>(null) }
    val hostOrigin = "https://appassets.androidplatform.net"
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }
    val callUrl = buildElementCallUrl(
        baseUrl = callBaseUrl,
        roomId = roomId,
        userId = appViewModel.currentUserId,
        deviceId = effectiveDeviceId,
        homeserverUrl = homeserverBaseUrl,
        perParticipantE2EE = isEncrypted,
        theme = theme,
        widgetId = "app.andromuks.call",
        parentOrigin = hostOrigin
    )
    val hostUrl = "https://appassets.androidplatform.net/assets/element_call_host.html?url=${
        URLEncoder.encode(callUrl, StandardCharsets.UTF_8.toString())
    }&widgetId=${URLEncoder.encode("app.andromuks.call", StandardCharsets.UTF_8.toString())}" +
        "&roomId=${URLEncoder.encode(roomId, StandardCharsets.UTF_8.toString())}" +
        "&userId=${URLEncoder.encode(appViewModel.currentUserId, StandardCharsets.UTF_8.toString())}" +
        "&deviceId=${URLEncoder.encode(effectiveDeviceId, StandardCharsets.UTF_8.toString())}" +
        "&baseUrl=${URLEncoder.encode(homeserverBaseUrl, StandardCharsets.UTF_8.toString())}" +
        "&perParticipantE2EE=${URLEncoder.encode(isEncrypted.toString(), StandardCharsets.UTF_8.toString())}" +
        "&theme=${URLEncoder.encode(theme, StandardCharsets.UTF_8.toString())}" +
        "&intent=join_existing" +
        "&hideHeader=true" +
        "&confineToRoom=true" +
        "&appPrompt=false" +
        "&lang=en" +
        "&fontScale=1" +
        "&rageshakeSubmitUrl=${
            URLEncoder.encode(
                "https://element.io/bugreports/submit",
                StandardCharsets.UTF_8.toString()
            )
        }" +
        "&preload=false"

    DisposableEffect(Unit) {
        appViewModel.setCallActive(true)
        appViewModel.setCallReadyForPip(false)
        appViewModel.setWidgetToDeviceHandler { payload ->
            val webView = callWebView.value ?: return@setWidgetToDeviceHandler
            val jsPayload = JSONObject.quote(JSONObject.wrap(payload)?.toString() ?: "null")
            webView.post {
                webView.evaluateJavascript(
                    "window.__andromuksWidgetHost.onNativeToDevice($jsPayload);",
                    null
                )
            }
        }
        onDispose {
            appViewModel.setCallActive(false)
            appViewModel.setCallReadyForPip(false)
            appViewModel.setWidgetToDeviceHandler(null)
        }
    }

    LaunchedEffect(roomId) {
        if (appViewModel.getRoomState(roomId) == null) {
            appViewModel.requestRoomState(roomId)
        }
    }

    BackHandler {
        val activity = findActivity(context)
        if (activity != null && canEnterPip(activity) && appViewModel.isCallReadyForPip()) {
            try {
                activity.enterPictureInPictureMode(
                    buildPipParams(activity)
                )
            } catch (e: IllegalStateException) {
                android.util.Log.w("Andromuks", "ElementCallScreen: PiP not supported on back", e)
                navController.popBackStack()
            }
        } else {
            navController.popBackStack()
        }
    }

    Scaffold(modifier = modifier) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        WebView.setWebContentsDebuggingEnabled(true)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.javaScriptCanOpenWindowsAutomatically = true

                        setBackgroundColor(android.graphics.Color.WHITE)

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val uri = request?.url ?: return null
                                return assetLoader.shouldInterceptRequest(uri)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading.value = false
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d("Andromuks", "ElementCallScreen: Page loaded $url")
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.e(
                                        "Andromuks",
                                        "ElementCallScreen: WebView error url=${request?.url} code=${error?.errorCode} desc=${error?.description}"
                                    )
                                }
                                if (request?.isForMainFrame == true) {
                                    loadError.value = error?.description?.toString() ?: "WebView load error"
                                    isLoading.value = false
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                errorResponse: android.webkit.WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.e(
                                        "Andromuks",
                                        "ElementCallScreen: WebView HTTP error url=${request?.url} status=${errorResponse?.statusCode}"
                                    )
                                }
                                if (request?.isForMainFrame == true) {
                                    loadError.value = "HTTP ${errorResponse?.statusCode ?: 0}"
                                    isLoading.value = false
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                android.util.Log.d(
                                    "Andromuks",
                                    "ElementCallScreen: console ${consoleMessage.message()} @${consoleMessage.lineNumber()}"
                                )
                                return super.onConsoleMessage(consoleMessage)
                            }

                            override fun onPermissionRequest(request: PermissionRequest) {
                                val audioGranted = ContextCompat.checkSelfPermission(
                                    ctx,
                                    Manifest.permission.RECORD_AUDIO
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val cameraGranted = ContextCompat.checkSelfPermission(
                                    ctx,
                                    Manifest.permission.CAMERA
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                if (audioGranted && cameraGranted) {
                                    request.grant(request.resources)
                                } else {
                                    pendingWebPermissionRequest.value = request
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.RECORD_AUDIO,
                                            Manifest.permission.CAMERA
                                        )
                                    )
                                }
                            }
                        }

                        callWebView.value = this

                        addJavascriptInterface(
                            ElementCallJsBridge(
                                webView = this,
                                roomId = roomId,
                                appViewModel = appViewModel,
                                onCallEnded = {
                                    navController.popBackStack()
                                },
                                onAlwaysOnScreen = { enabled ->
                                    val activity = findActivity(ctx)
                                    if (enabled &&
                                        activity != null &&
                                        canEnterPip(activity) &&
                                        appViewModel.isCallReadyForPip()
                                    ) {
                                        try {
                                            activity.enterPictureInPictureMode(
                                                buildPipParams(activity)
                                            )
                                        } catch (e: IllegalStateException) {
                                            android.util.Log.w(
                                                "Andromuks",
                                                "ElementCallScreen: PiP not supported for this activity",
                                                e
                                            )
                                        }
                                    }
                                }
                            ),
                            "AndroidWidgetBridge"
                        )

                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("Andromuks", "ElementCallScreen: hostUrl=$hostUrl")
                            android.util.Log.d("Andromuks", "ElementCallScreen: callUrl=$callUrl")
                            android.util.Log.d("Andromuks", "ElementCallScreen: callBaseUrl=$callBaseUrl")
                        }
                        loadUrl(hostUrl)
                        lastLoadedUrl.value = hostUrl
                    }
                },
                update = { webView ->
                    if (hostUrl.isNotBlank() && hostUrl != lastLoadedUrl.value) {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "ElementCallScreen: reloading hostUrl=$hostUrl"
                            )
                        }
                        webView.loadUrl(hostUrl)
                        lastLoadedUrl.value = hostUrl
                    }
                }
            )

            if (isLoading.value && loadError.value == null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading call…")
                    }
                }
            }

            loadError.value?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Failed to load call: $error")
                    }
                }
            }
        }
    }
}

private fun buildElementCallUrl(
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
    val callBase = if (needsRoomSuffix) {
        baseUri.buildUpon().appendPath("room").build()
    } else {
        baseUri
    }

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

private fun deriveHomeserverBaseUrl(
    configuredBaseUrl: String,
    userId: String
): String {
    if (configuredBaseUrl.isNotBlank()) return configuredBaseUrl
    val domain = userId.substringAfter(":", "").trim()
    if (domain.isBlank()) return ""
    return "https://$domain"
}

private fun isRoomEncryptedFromState(events: JSONArray?): Boolean? {
    if (events == null) return null
    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        if (event.optString("type") == "m.room.encryption") {
            val content = event.optJSONObject("content")
            val algorithm = content?.optString("algorithm").orEmpty()
            if (algorithm.isNotBlank()) {
                return true
            }
        }
    }
    return false
}

private class ElementCallJsBridge(
    private val webView: WebView,
    private val roomId: String,
    private val appViewModel: AppViewModel,
    private val onCallEnded: () -> Unit,
    private val onAlwaysOnScreen: (Boolean) -> Unit
) {
    private val syntheticDelayIds = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    // Track when the ElementCall screen was opened to ignore old disconnect events
    private val screenOpenTimestamp = System.currentTimeMillis()

    @JavascriptInterface
    fun postMessage(message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "ElementCallJsBridge: received $message")
        }
        val json = try {
            JSONObject(message)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "ElementCallJsBridge: Invalid JSON message: $message", e)
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
            android.util.Log.w("Andromuks", "ElementCallJsBridge: Missing requestId for action=$action")
            return
        }

        if (requestData is JSONObject &&
            (normalizedAction.contains("send_event") ||
                normalizedAction.contains("send_state") ||
                normalizedAction.contains("set_state"))
        ) {
            val eventType = requestData.optString("type")
            if (eventType == "org.matrix.msc3401.call.member") {
                val deviceId = appViewModel.deviceId.ifBlank {
                    appViewModel.getDeviceID().orEmpty()
                }
                if (!requestData.has("state_key") && deviceId.isNotBlank()) {
                    requestData.put(
                        "state_key",
                        "_${appViewModel.currentUserId}_${deviceId}_m.call"
                    )
                }
                val content = requestData.optJSONObject("content") ?: JSONObject().also {
                    requestData.put("content", it)
                }
                if (content.length() > 0 && !content.has("membershipID") && deviceId.isNotBlank()) {
                    content.put(
                        "membershipID",
                        "${appViewModel.currentUserId}:${deviceId}"
                    )
                }
                if (content.length() > 0) {
                    appViewModel.setCallReadyForPip(true)
                }
            }
        }

        if (normalizedAction.contains("update_delayed_event")) {
            val delayId = (requestData as? JSONObject)?.optString("delay_id").orEmpty()
            if (delayId.isNotBlank() && (delayId.startsWith("andromuks-") || syntheticDelayIds.remove(delayId) == true)) {
                sendWidgetResponse(action, widgetRequestId, JSONObject())
                return
            }
        }

        if (normalizedAction.contains("hangup") ||
            normalizedAction.contains("leave_call") ||
            normalizedAction.contains("call_ended")
        ) {
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
                if (delayMs > 0L) {
                    requestData.put("delay_ms", delayMs)
                }
            }
            if (command == "send_to_device") {
                if (!requestData.has("event_type")) {
                    val eventType = requestData.optString("type")
                    if (eventType.isNotBlank()) {
                        requestData.put("event_type", eventType)
                        // Remove 'type' field - gomuks expects only 'event_type'
                        requestData.remove("type")
                    }
                } else {
                    // If event_type exists, ensure 'type' is removed
                    requestData.remove("type")
                }
            }
            ensureRoomId(requestData, roomId)
        } else {
            ensureRoomId(requestData, roomId)
        }

        appViewModel.sendWidgetCommand(command, payload) { result ->
            result.onSuccess { response ->
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "ElementCallJsBridge: response for $action -> $response")
                }
                val normalizedResponse = normalizeWidgetResponse(action, response, requestData)
                sendWidgetResponse(action, widgetRequestId, normalizedResponse)
            }.onFailure { error ->
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "ElementCallJsBridge: error for $action -> ${error.message}")
                }
                sendWidgetError(action, widgetRequestId, error.message ?: "Unknown error")
            }
        }
    }

    private fun mapWidgetActionToCommand(action: String, requestData: Any?): String? {
        val normalized = action.lowercase()
        if (normalized.contains("send_event") && requestData is JSONObject) {
            if (requestData.has("state_key")) {
                return "set_state"
            }
        }
        return when {
            normalized.contains("send_state") || normalized.contains("set_state") -> "set_state"
            normalized.contains("send_event") -> "send_event"
            normalized.contains("send_to_device") || normalized.contains("send.to_device") -> "send_to_device"
            normalized.contains("openid") -> "request_openid_token"
            normalized.contains("listen_to_device") ||
                normalized.contains("receive_to_device") ||
                normalized.contains("receive.to_device") -> "listen_to_device"
            normalized.contains("update_delayed_event") -> "update_delayed_event"
            normalized.contains("read_state") || normalized.contains("get_state") -> "get_room_state"
            normalized.contains("read_events") -> "get_room_state"
            else -> null
        }
    }

    private fun ensureRoomId(data: Any?, roomId: String): Any? {
        if (data is JSONObject) {
            if (!data.has("room_id")) {
                data.put("room_id", roomId)
            }
            return data
        }
        if (data == null) {
            return JSONObject().put("room_id", roomId)
        }
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

    private fun normalizeWidgetResponse(
        action: String,
        response: Any?,
        requestData: Any?
    ): Any? {
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
                for (i in 0 until roomIds.length()) {
                    roomIds.optString(i).takeIf { it.isNotBlank() }?.let { ids.add(it) }
                }
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
                
                // Check call.member events with empty content (members who left)
                if (normalizedEvent.optString("type") == "org.matrix.msc3401.call.member") {
                    val content = normalizedEvent.optJSONObject("content")
                    if (content == null || content.length() == 0) {
                        // Member has left - check if it's us and close call screen if so
                        val stateKey = normalizedEvent.optString("state_key")
                        val membershipParts = extractMembershipParts(stateKey)
                        
                        if (membershipParts != null) {
                            val (eventUserId, eventDeviceId) = membershipParts
                            val ourDeviceId = appViewModel.deviceId.ifBlank {
                                appViewModel.getDeviceID().orEmpty()
                            }
                            
                            // Only check if it's our own device (not another device from our user)
                            if (eventUserId == appViewModel.currentUserId && eventDeviceId == ourDeviceId) {
                                // Check event timestamp is after screen was opened (ignore old disconnects)
                                val eventTimestamp = normalizedEvent.optLong("origin_server_ts", 0L).takeIf { it > 0 }
                                    ?: normalizedEvent.optLong("timestamp", 0L)
                                
                                if (eventTimestamp > screenOpenTimestamp) {
                                    // We left the call after screen opened - close the screen
                                    android.util.Log.d("Andromuks", "ElementCallJsBridge: Detected our own call.member disconnect (device=$eventDeviceId, timestamp=$eventTimestamp > screenOpen=$screenOpenTimestamp), closing call screen")
                                    webView.post {
                                        onCallEnded()
                                    }
                                    // Don't send our own disconnect to Element Call (we're closing anyway)
                                    continue
                                } else {
                                    android.util.Log.d("Andromuks", "ElementCallJsBridge: Ignoring old disconnect event (timestamp=$eventTimestamp <= screenOpen=$screenOpenTimestamp)")
                                }
                            }
                        }
                        // For other users' disconnects, include the event so Element Call knows they left
                        // Empty content = member left, which Element Call will handle correctly
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
            if (!obj.has("state")) {
                obj.put("state", "allowed")
            }
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
                if (delayId.isNotBlank()) {
                    return JSONObject().put("delay_id", delayId)
                }
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
                        val eventId = response.optString("eventId").takeIf { it.isNotBlank() }
                        if (eventId != null) {
                            response.put("event_id", eventId)
                        }
                    }
                    response
                }
                is Map<*, *> -> {
                    val obj = JSONObject(response)
                    if (!obj.has("event_id")) {
                        val eventId = obj.optString("eventId").takeIf { it.isNotBlank() }
                        if (eventId != null) {
                            obj.put("event_id", eventId)
                        }
                    }
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
        if (decryptedType != null) {
            event.put("type", decryptedType)
        } else {
            raw.optString("type").takeIf { it.isNotBlank() }?.let { event.put("type", it) }
        }
        raw.optString("sender").takeIf { it.isNotBlank() }?.let { event.put("sender", it) }
        if (raw.has("state_key")) {
            event.put("state_key", raw.optString("state_key"))
        }
        raw.optString("room_id").takeIf { it.isNotBlank() }?.let { event.put("room_id", it) }
        val originTs = if (raw.has("origin_server_ts")) {
            raw.optLong("origin_server_ts", 0L)
        } else {
            raw.optLong("timestamp", 0L)
        }
        if (originTs > 0) {
            event.put("origin_server_ts", originTs)
        }
        if (decryptedType != null && raw.has("decrypted")) {
            event.put("content", raw.opt("decrypted"))
        } else if (raw.has("content")) {
            event.put("content", raw.opt("content"))
        }
        val contentObject = event.optJSONObject("content")
        // For call.member events, don't use prev_content - empty content means member left
        // For other events, use prev_content as fallback
        if (contentObject != null && contentObject.length() == 0 && 
            event.optString("type") != "org.matrix.msc3401.call.member") {
            val unsigned = raw.optJSONObject("unsigned")
            val prevContent = unsigned?.optJSONObject("prev_content")
            if (prevContent != null && prevContent.length() > 0) {
                event.put("content", prevContent)
            }
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
                    if (!userId.isNullOrBlank() && !deviceId.isNullOrBlank()) {
                        content.put("membershipID", "$userId:$deviceId")
                    }
                }
            }
        }
        if (raw.has("unsigned")) {
            event.put("unsigned", raw.opt("unsigned"))
        }
        return event
    }

    private fun buildTimelineEventsResponse(requestData: Any?): JSONObject {
        val filter = requestData as? JSONObject
        val roomId = filter?.optString("room_id")?.takeIf { it.isNotBlank() }
        val roomIds = filter?.optJSONArray("room_ids")?.let { ids ->
            val list = mutableListOf<String>()
            for (i in 0 until ids.length()) {
                ids.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
            }
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
            for (i in start until eventsArray.length()) {
                trimmed.put(eventsArray.get(i))
            }
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
            webView.evaluateJavascript(
                "window.__andromuksWidgetHost.onNativeResponse($jsPayload);",
                null
            )
        }
    }

    private fun extractMembershipParts(stateKey: String?): Pair<String, String>? {
        if (stateKey.isNullOrBlank()) return null
        if (!stateKey.startsWith("_") || !stateKey.endsWith("_m.call")) return null
        val trimmed = stateKey.removePrefix("_").removeSuffix("_m.call")
        val splitIndex = trimmed.lastIndexOf('_')
        if (splitIndex <= 0 || splitIndex >= trimmed.length - 1) return null
        val userId = trimmed.substring(0, splitIndex)
        val deviceId = trimmed.substring(splitIndex + 1)
        if (userId.isBlank() || deviceId.isBlank()) return null
        return userId to deviceId
    }
}

private fun findActivity(context: Context): Activity? {
    return when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}

private fun canEnterPip(activity: Activity): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val hasFeature = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    return hasFeature
}

private fun buildPipParams(activity: Activity): PictureInPictureParams {
    val view = activity.window.decorView
    val width = view.width.coerceAtLeast(1)
    val height = view.height.coerceAtLeast(1)
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational(width, height))
        .build()
}

