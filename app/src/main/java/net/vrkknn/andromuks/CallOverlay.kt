package net.vrkknn.andromuks

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.webkit.WebViewAssetLoader
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Persistent overlay that owns the Element Call WebView for its entire lifetime.
 *
 * Foreground (callMiniPipActive=false): renders full-screen at zIndex 10, covering the NavHost.
 * Background (callMiniPipActive=true):  stays full-size but at zIndex -1, behind the NavHost.
 *   The WebView is never removed from the view hierarchy, so WebRTC audio/video keep running.
 *
 * Back gesture while foregrounded → puts call in background (room timeline becomes interactive).
 * "Return to call" button in RoomHeader → calls setCallMiniPip(false) to foreground the call.
 */
@Composable
fun CallOverlay(appViewModel: AppViewModel) {
    // Must be declared before any early return (Compose composition rules).
    val pendingWebPermissionRequest = remember { mutableStateOf<PermissionRequest?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        pendingWebPermissionRequest.value?.let { request ->
            if (granted) request.grant(request.resources) else request.deny()
        }
        pendingWebPermissionRequest.value = null
    }

    val isActive = appViewModel.callActiveInternal
    val isMiniPip = appViewModel.callMiniPipActive
    val roomId = appViewModel.callActiveRoomId

    // Stable composition slot — always registered, only enabled when the call is full-screen.
    BackHandler(enabled = isActive && !isMiniPip) {
        appViewModel.setCallMiniPip(true, roomId)
    }

    if (!isActive || roomId.isEmpty()) return

    val context = LocalContext.current
    val callWebView = remember { mutableStateOf<WebView?>(null) }
    val lastLoadedUrl = remember { mutableStateOf<String?>(null) }
    val isLoading = remember { mutableStateOf(true) }
    val loadError = remember { mutableStateOf<String?>(null) }

    val isEncrypted = isRoomEncryptedFromState(appViewModel.getRoomState(roomId)) ?: false
    val effectiveDeviceId = appViewModel.deviceId.ifBlank { appViewModel.getDeviceID().orEmpty() }
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
    val hostUrl = "https://appassets.androidplatform.net/assets/element_call_host.html" +
        "?url=${URLEncoder.encode(callUrl, StandardCharsets.UTF_8.toString())}" +
        "&widgetId=${URLEncoder.encode("app.andromuks.call", StandardCharsets.UTF_8.toString())}" +
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
        "&rageshakeSubmitUrl=${URLEncoder.encode("https://element.io/bugreports/submit", StandardCharsets.UTF_8.toString())}" +
        "&preload=false"

    DisposableEffect(Unit) {
        appViewModel.setWidgetToDeviceHandler { payload ->
            val webView = callWebView.value ?: return@setWidgetToDeviceHandler
            val jsPayload = JSONObject.quote(JSONObject.wrap(payload)?.toString() ?: "null")
            webView.post {
                webView.evaluateJavascript(
                    "window.__andromuksWidgetHost.onNativeToDevice($jsPayload);", null
                )
            }
        }
        onDispose {
            appViewModel.setWidgetToDeviceHandler(null)
            appViewModel.callPersistentWebView = null
        }
    }

    LaunchedEffect(roomId) {
        if (appViewModel.getRoomState(roomId) == null) {
            appViewModel.requestRoomState(roomId)
        }
    }

    // Foreground: zIndex 10 → on top of NavHost, fully interactive.
    // Background: zIndex -1 → behind NavHost; room timeline is visible and interactive.
    //   The WebView stays in the hierarchy at full size so WebRTC keeps running.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (!isMiniPip) 10f else -1f)
    ) {
        AndroidView(
            factory = { ctx ->
                @Suppress("DEPRECATION")
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
                            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "CallOverlay: page loaded $url")
                        }
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (BuildConfig.DEBUG) android.util.Log.e("Andromuks", "CallOverlay: WebView error ${error?.description}")
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
                            if (request?.isForMainFrame == true) {
                                loadError.value = "HTTP ${errorResponse?.statusCode ?: 0}"
                                isLoading.value = false
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            android.util.Log.d("Andromuks", "CallOverlay: console ${consoleMessage.message()} @${consoleMessage.lineNumber()}")
                            return super.onConsoleMessage(consoleMessage)
                        }
                        override fun onPermissionRequest(request: PermissionRequest) {
                            val audioGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            val cameraGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            if (audioGranted && cameraGranted) {
                                request.grant(request.resources)
                            } else {
                                pendingWebPermissionRequest.value = request
                                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                            }
                        }
                    }

                    appViewModel.callPersistentWebView = this
                    callWebView.value = this

                    addJavascriptInterface(
                        ElementCallJsBridge(
                            webView = this,
                            roomId = roomId,
                            appViewModel = appViewModel,
                            onCallEnded = { appViewModel.endCall() },
                            onAlwaysOnScreen = { /* no system PiP */ }
                        ),
                        "AndroidWidgetBridge"
                    )

                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("Andromuks", "CallOverlay: hostUrl=$hostUrl")
                        android.util.Log.d("Andromuks", "CallOverlay: callBaseUrl=$callBaseUrl")
                    }
                    loadUrl(hostUrl)
                    lastLoadedUrl.value = hostUrl
                }
            },
            update = { webView ->
                if (hostUrl.isNotBlank() && hostUrl != lastLoadedUrl.value) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "CallOverlay: reloading hostUrl")
                    webView.loadUrl(hostUrl)
                    lastLoadedUrl.value = hostUrl
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading / error overlays only shown in foreground.
        if (!isMiniPip) {
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Failed to load call: $error")
                    }
                }
            }
        }
    }
}
