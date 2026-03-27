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
            appContext = context
            RoomTimelineCache.setAppContext(context)

            if (!skipCacheClear) {
                clearCurrentRoomId()
                if (BuildConfig.DEBUG)
                    android.util.Log.d("Andromuks", "AppViewModel: Cleared current room ID on app startup")
            } else {
                if (BuildConfig.DEBUG)
                    android.util.Log.d(
                        "Andromuks",
                        "AppViewModel: Skipping cache clear on app startup (opening from notification)"
                    )
            }

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
                val registrationRequestId = requestIdCounter++
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
