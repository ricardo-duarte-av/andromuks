package net.vrkknn.andromuks

import org.json.JSONObject

/**
 * Device / encryption info WebSocket requests and response parsing — [AppViewModel].
 */
internal class UserEncryptionCoordinator(private val vm: AppViewModel) {

    fun requestUserEncryptionInfo(
        userId: String,
        callback: (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit,
    ) = with(vm) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Requesting encryption info for user: $userId")

        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected")
            callback(null, "WebSocket not connected")
            return@with
        }

        val requestId = requestIdCounter++
        userEncryptionInfoRequests[requestId] = callback

        sendWebSocketCommand(
            "get_profile_encryption_info",
            requestId,
            mapOf("user_id" to userId),
        )
    }

    fun trackUserDevices(
        userId: String,
        callback: (net.vrkknn.andromuks.utils.UserEncryptionInfo?, String?) -> Unit,
    ) = with(vm) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Tracking devices for user: $userId")

        if (!isWebSocketConnected()) {
            android.util.Log.w("Andromuks", "AppViewModel: WebSocket not connected")
            callback(null, "WebSocket not connected")
            return@with
        }

        val requestId = requestIdCounter++
        trackDevicesRequests[requestId] = callback

        sendWebSocketCommand(
            "track_user_devices",
            requestId,
            mapOf("user_id" to userId),
        )
    }

    fun handleUserEncryptionInfoResponse(requestId: Int, data: Any) = with(vm) {
        val callback = userEncryptionInfoRequests.remove(requestId) ?: return@with
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling encryption info response for requestId: $requestId")

        try {
            val encInfo = parseUserEncryptionInfo(data)
            callback(encInfo, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing encryption info", e)
            callback(null, "Error: ${e.message}")
        }
    }

    fun handleTrackDevicesResponse(requestId: Int, data: Any) = with(vm) {
        val callback = trackDevicesRequests.remove(requestId) ?: return@with
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Handling track devices response for requestId: $requestId")

        try {
            val encInfo = parseUserEncryptionInfo(data)
            callback(encInfo, null)
        } catch (e: Exception) {
            android.util.Log.e("Andromuks", "AppViewModel: Error parsing track devices response", e)
            callback(null, "Error: ${e.message}")
        }
    }

    private fun parseUserEncryptionInfo(data: Any): net.vrkknn.andromuks.utils.UserEncryptionInfo {
        val jsonData = when (data) {
            is JSONObject -> data
            else -> JSONObject(data.toString())
        }

        val devicesTracked = jsonData.optBoolean("devices_tracked", false)
        val devicesArray = jsonData.optJSONArray("devices")
        val devices = if (devicesArray != null) {
            val list = mutableListOf<net.vrkknn.andromuks.utils.DeviceInfo>()
            for (i in 0 until devicesArray.length()) {
                val deviceJson = devicesArray.getJSONObject(i)
                list.add(
                    net.vrkknn.andromuks.utils.DeviceInfo(
                        deviceId = deviceJson.getString("device_id"),
                        name = deviceJson.getString("name"),
                        identityKey = deviceJson.getString("identity_key"),
                        signingKey = deviceJson.getString("signing_key"),
                        fingerprint = deviceJson.getString("fingerprint"),
                        trustState = deviceJson.getString("trust_state"),
                    ),
                )
            }
            list
        } else {
            null
        }

        return net.vrkknn.andromuks.utils.UserEncryptionInfo(
            devicesTracked = devicesTracked,
            devices = devices,
            masterKey = jsonData.optString("master_key")?.takeIf { it.isNotBlank() },
            firstMasterKey = jsonData.optString("first_master_key")?.takeIf { it.isNotBlank() },
            userTrusted = jsonData.optBoolean("user_trusted", false),
            errors = jsonData.opt("errors"),
        )
    }
}
