package net.vrkknn.andromuks

import android.Manifest
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
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

    val roomName = appViewModel.getRoomById(roomId)?.name ?: "Call"
    val isEncrypted = appViewModel.currentRoomState?.isEncrypted ?: false
    val configuredCallBaseUrl = appViewModel.elementCallBaseUrl.trim()
    val backendEmbeddedUrl = appViewModel.homeserverUrl.trimEnd('/') + "/element-call-embedded/"
    val callBaseUrl = when {
        configuredCallBaseUrl.contains("element-call-embedded") -> configuredCallBaseUrl
        appViewModel.homeserverUrl.isNotBlank() -> backendEmbeddedUrl
        configuredCallBaseUrl.isNotBlank() -> configuredCallBaseUrl
        else -> "https://call.element.io/"
    }
    val homeserverBaseUrl = deriveHomeserverBaseUrl(
        appViewModel.realMatrixHomeserverUrl,
        appViewModel.currentUserId
    )
    val theme = if (isSystemInDarkTheme()) "dark" else "light"
    val isLoading = remember { mutableStateOf(true) }
    val loadError = remember { mutableStateOf<String?>(null) }
    val hostOrigin = "https://appassets.androidplatform.net"
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(roomName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
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

                        addJavascriptInterface(
                            ElementCallJsBridge(
                                webView = this,
                                roomId = roomId,
                                appViewModel = appViewModel
                            ),
                            "AndroidWidgetBridge"
                        )

                        val callUrl = buildElementCallUrl(
                            baseUrl = callBaseUrl,
                            roomId = roomId,
                            userId = appViewModel.currentUserId,
                            deviceId = appViewModel.deviceId,
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
                            "&deviceId=${URLEncoder.encode(appViewModel.deviceId, StandardCharsets.UTF_8.toString())}" +
                            "&baseUrl=${URLEncoder.encode(homeserverBaseUrl, StandardCharsets.UTF_8.toString())}" +
                            "&perParticipantE2EE=${URLEncoder.encode(isEncrypted.toString(), StandardCharsets.UTF_8.toString())}" +
                            "&theme=${URLEncoder.encode(theme, StandardCharsets.UTF_8.toString())}" +
                            "&intent=join_existing" +
                            "&hideHeader=false" +
                            "&confineToRoom=true" +
                            "&appPrompt=true" +
                            "&lang=en" +
                            "&fontScale=1" +
                            "&rageshakeSubmitUrl=${
                                URLEncoder.encode(
                                    "https://element.io/bugreports/submit",
                                    StandardCharsets.UTF_8.toString()
                                )
                            }" +
                            "&preload=false"
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("Andromuks", "ElementCallScreen: hostUrl=$hostUrl")
                            android.util.Log.d("Andromuks", "ElementCallScreen: callUrl=$callUrl")
                            android.util.Log.d("Andromuks", "ElementCallScreen: callBaseUrl=$callBaseUrl")
                        }
                        loadUrl(hostUrl)
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

private class ElementCallJsBridge(
    private val webView: WebView,
    private val roomId: String,
    private val appViewModel: AppViewModel
) {
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
        val widgetRequestId = json.opt("requestId")?.toString()?.takeIf { it.isNotBlank() }
        val data = json.opt("data")

        if (widgetRequestId == null) {
            android.util.Log.w("Andromuks", "ElementCallJsBridge: Missing requestId for action=$action")
            return
        }

        val command = mapWidgetActionToCommand(action)
        if (command == null) {
            sendWidgetError(action, widgetRequestId, "Unsupported widget action: $action")
            return
        }

        val payload = ensureRoomId(data, roomId)

        appViewModel.sendWidgetCommand(command, payload) { result ->
            result.onSuccess { response ->
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Andromuks", "ElementCallJsBridge: response for $action -> $response")
                }
                val normalizedResponse = normalizeWidgetResponse(action, response, data)
                sendWidgetResponse(action, widgetRequestId, normalizedResponse)
            }.onFailure { error ->
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("Andromuks", "ElementCallJsBridge: error for $action -> ${error.message}")
                }
                sendWidgetError(action, widgetRequestId, error.message ?: "Unknown error")
            }
        }
    }

    private fun mapWidgetActionToCommand(action: String): String? {
        val normalized = action.lowercase()
        return when {
            normalized.contains("send_state") || normalized.contains("set_state") -> "set_state"
            normalized.contains("send_event") -> "send_event"
            normalized.contains("send_to_device") -> "send_to_device"
            normalized.contains("openid") -> "request_openid_token"
            normalized.contains("listen_to_device") || normalized.contains("receive_to_device") -> "listen_to_device"
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

    private fun normalizeWidgetResponse(action: String, response: Any?, requestData: Any?): Any? {
        val normalized = action.lowercase()
        if (normalized.contains("read_events") || normalized.contains("read_state") || normalized.contains("get_state")) {
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
        return response
    }

    private fun normalizeMatrixEvent(raw: JSONObject): JSONObject {
        val event = JSONObject()
        raw.optString("event_id").takeIf { it.isNotBlank() }?.let { event.put("event_id", it) }
        raw.optString("type").takeIf { it.isNotBlank() }?.let { event.put("type", it) }
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
        if (raw.has("content")) {
            event.put("content", raw.opt("content"))
        }
        if (raw.has("unsigned")) {
            event.put("unsigned", raw.opt("unsigned"))
        }
        return event
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
}

