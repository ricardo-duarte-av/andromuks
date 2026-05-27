package net.vrkknn.andromuks

import android.content.Context

/**
 * FCM / push registration orchestration for [AppViewModel].
 */
internal class FcmPushCoordinator(private val vm: AppViewModel) {

    fun initializeFCM(
        context: Context,
        homeserverUrl: String = "",
        authToken: String = "",
        skipCacheClear: Boolean = false
    ) {
        with(vm) {
            if (!WebSocketService.isViewModelRegistered(viewModelId)) {
                WebSocketService.registerViewModel(viewModelId, isPrimary = false)
            }
            val appCtx = context.applicationContext
            appContext = appCtx
            RoomTimelineCache.setAppContext(appCtx)

            // Historically this called clearCurrentRoomId() (gated on !skipCacheClear) on the
            // assumption that "FCM init = app startup, no room is open yet". That assumption
            // breaks for every direct-to-room entry path. Most of those callers dispatch
            // initializeFCM onto Dispatchers.IO (AuthCheck, MainActivity, ChatBubbleActivity) so
            // by the time the IO worker actually runs, the screen's LaunchedEffect(roomId) has
            // already set currentRoomId to the target room and processCachedEvents has scheduled
            // a Default-thread timeline rebuild. The clear then races the rebuild's stale-write
            // guard, the rebuild is discarded, timelineEvents and eventChainMap end up empty,
            // and the screen renders "Room loading…" forever. Confirmed via stack-trace logging
            // (🔴 clearCurrentRoomId caller stack) on a release build repro 2026-05-27.
            //
            // FCM init has no business mutating the open-room state — those are orthogonal
            // concerns. Any caller that legitimately needs to clear currentRoomId can do it
            // explicitly; nothing in the current call graph relies on this side-effect.
            // The skipCacheClear parameter is preserved on the signature for now to avoid a
            // churny rename across all call sites.

            loadPendingOperationsFromStorage()

            val components =
                FCMNotificationManager.initializeComponents(
                    context = context,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    realMatrixHomeserverUrl = realMatrixHomeserverUrl
                )
            fcmNotificationManager = components.fcmNotificationManager
            conversationsApi = components.conversationsApi
            personsApi = components.personsApi
            webClientPushIntegration = components.webClientPushIntegration

            startAcknowledgmentTimeoutCheck()
            startAcknowledgedMessagesCleanup()
        }
    }

    fun registerFCMNotifications() {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: registerFCMNotifications called")
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: fcmNotificationManager=${fcmNotificationManager != null}, homeserverUrl=$homeserverUrl, authToken=${authToken.take(20)}..., currentUserId=$currentUserId"
                )

            fcmNotificationManager?.let { manager ->
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Calling FCMNotificationManager.registerNotifications"
                    )
                FCMNotificationManager.registerNotifications(
                    fcmNotificationManager = manager,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    currentUserId = currentUserId,
                    onTokenReady = {
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: FCM token ready callback triggered, registering with Gomuks Backend"
                            )
                        this@FcmPushCoordinator.registerFCMWithGomuksBackend()
                    }
                )
            }
                ?: run {
                    android.util.Log.w(
                        "Andromuks",
                        "AppViewModel: fcmNotificationManager is null, cannot register FCM notifications"
                    )
                }
        }
    }

    fun getFCMTokenForGomuksBackend(): String? {
        with(vm) {
            return fcmNotificationManager?.getTokenForGomuksBackend()
        }
    }

    fun registerFCMWithGomuksBackend(
        forceRegistrationOnConnect: Boolean = false,
        forceNow: Boolean = false
    ) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: registerFCMWithGomuksBackend called (forceRegistrationOnConnect=$forceRegistrationOnConnect, forceNow=$forceNow)"
                )

            // forceRegistrationOnConnect guarantees re-registration on every (re)connection;
            // bypass the debounce so a quick reconnect doesn't silently skip registration.
            if (!forceNow && !forceRegistrationOnConnect) {
                val now = System.currentTimeMillis()
                val timeSinceLastRegistration = now - lastFCMRegistrationTime
                if (timeSinceLastRegistration < AppViewModel.FCM_REGISTRATION_DEBOUNCE_MS) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Skipping FCM registration - only ${timeSinceLastRegistration}ms since last registration (debounce: ${AppViewModel.FCM_REGISTRATION_DEBOUNCE_MS}ms)"
                        )
                    return
                }
            }

            if (!forceRegistrationOnConnect && !forceNow) {
                val shouldRegister = shouldRegisterPush()
                if (BuildConfig.DEBUG)
                    android.util.Log.d("Andromuks", "AppViewModel: shouldRegisterPush() returned $shouldRegister")
                val hasRegisteredViaWebSocket =
                    appContext?.let { context ->
                        context
                            .getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                            .getBoolean("fcm_registered_via_websocket", false)
                    }
                        ?: false
                val forceRegistration = !hasRegisteredViaWebSocket
                if (!shouldRegister && !forceRegistration) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Skipping FCM registration - not due yet and already registered via WebSocket"
                        )
                    return
                }
            }

            val token = fcmNotificationManager?.getTokenForGomuksBackend()
            val deviceId = webClientPushIntegration?.getLocalDeviceID()
            val encryptionKey = webClientPushIntegration?.getPushEncryptionKey()

            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: registerFCMWithGomuksBackend - token=${token?.take(20)}..., deviceId=$deviceId, encryptionKey=${encryptionKey?.take(20)}..."
                )

            if (token != null && deviceId != null && encryptionKey != null) {
                val registrationRequestId = WebSocketService.allocateRequestId()
                fcmRegistrationRequests[registrationRequestId] = "fcm_registration"

                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Registering FCM with request_id=$registrationRequestId"
                    )

                val expirationSeconds = System.currentTimeMillis() / 1000 + 86400
                val registrationData =
                    mapOf(
                        "type" to "fcm",
                        "device_id" to deviceId,
                        "data" to token,
                        "encryption" to
                            mapOf(
                                "key" to encryptionKey,
                                "expiration" to expirationSeconds
                            )
                    )

                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Sending WebSocket command: register_push with data: $registrationData"
                    )
                val result = sendWebSocketCommand("register_push", registrationRequestId, registrationData)

                if (result == WebSocketResult.SUCCESS) {
                    lastFCMRegistrationTime = System.currentTimeMillis()
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Sent FCM registration to Gomuks Backend with device_id=$deviceId (request_id=$registrationRequestId)"
                        )
                } else {
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: register_push queued or not sent (result=$result) - will be sent after init_complete or when connected"
                        )
                }

                appContext?.let { context ->
                    context
                        .getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("fcm_websocket_registration_attempted", true)
                        .apply()
                }
            } else {
                android.util.Log.w(
                    "Andromuks",
                    "AppViewModel: Missing required data for FCM registration - token=${token != null}, deviceId=${deviceId != null}, encryptionKey=${encryptionKey != null}"
                )
            }
        }
    }

    fun handleFCMRegistrationResponse(requestId: Int, data: Any) {
        with(vm) {
            if (BuildConfig.DEBUG)
                android.util.Log.d(
                    "Andromuks",
                    "AppViewModel: handleFCMRegistrationResponse called with requestId=$requestId, dataType=${data::class.java.simpleName}"
                )
            if (BuildConfig.DEBUG)
                android.util.Log.d("Andromuks", "AppViewModel: FCM registration response data: $data")

            fcmRegistrationRequests.remove(requestId)

            when (data) {
                is Boolean -> {
                    if (data) {
                        android.util.Log.i("Andromuks", "AppViewModel: FCM registration successful (boolean true)")
                        webClientPushIntegration?.markPushRegistrationCompleted()
                        appContext?.let { context ->
                            context
                                .getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("fcm_registered_via_websocket", true)
                                .apply()
                        }
                        if (BuildConfig.DEBUG)
                            android.util.Log.d("Andromuks", "AppViewModel: Marked FCM as registered via WebSocket")
                    } else {
                        android.util.Log.e("Andromuks", "AppViewModel: FCM registration failed (boolean false)")
                    }
                }
                is String -> {
                    android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (string): $data")
                    webClientPushIntegration?.markPushRegistrationCompleted()
                    appContext?.let { context ->
                        context
                            .getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("fcm_registered_via_websocket", true)
                            .apply()
                    }
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Marked FCM as registered via WebSocket (string response)"
                        )
                }
                is org.json.JSONObject -> {
                    android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (JSON): ${data.toString()}")
                    val success = data.optBoolean("success", true)
                    if (success) {
                        android.util.Log.i("Andromuks", "AppViewModel: FCM registration successful (JSON)")
                        webClientPushIntegration?.markPushRegistrationCompleted()
                        appContext?.let { context ->
                            context
                                .getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("fcm_registered_via_websocket", true)
                                .apply()
                        }
                        if (BuildConfig.DEBUG)
                            android.util.Log.d(
                                "Andromuks",
                                "AppViewModel: Marked FCM as registered via WebSocket (JSON response)"
                            )
                    } else {
                        android.util.Log.e("Andromuks", "AppViewModel: FCM registration failed (JSON)")
                    }
                }
                else -> {
                    android.util.Log.i("Andromuks", "AppViewModel: FCM registration response (unknown type): $data")
                    webClientPushIntegration?.markPushRegistrationCompleted()
                    appContext?.let { context ->
                        context
                            .getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("fcm_registered_via_websocket", true)
                            .apply()
                    }
                    if (BuildConfig.DEBUG)
                        android.util.Log.d(
                            "Andromuks",
                            "AppViewModel: Marked FCM as registered via WebSocket (unknown response type)"
                        )
                }
            }
        }
    }
}
