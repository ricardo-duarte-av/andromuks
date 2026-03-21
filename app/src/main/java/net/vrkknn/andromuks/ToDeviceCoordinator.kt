package net.vrkknn.andromuks

import org.json.JSONArray
import org.json.JSONObject

/**
 * Matrix `to_device` payload normalization and forwarding to the Element Call widget — [AppViewModel].
 */
internal class ToDeviceCoordinator(private val vm: AppViewModel) {

    fun setWidgetToDeviceHandler(handler: ((Any?) -> Unit)?) {
        vm.widgetToDeviceHandler = handler
    }

    fun handleToDeviceMessage(data: Any?) = with(vm) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: to_device received ${data?.toString()?.take(200)}")
        }
        widgetToDeviceHandler?.invoke(normalizeToDevicePayload(data))
    }

    internal fun handleSyncToDeviceEvents(syncJson: JSONObject) = with(vm) {
        val data = syncJson.optJSONObject("data") ?: syncJson
        val toDeviceValue = data.opt("to_device") ?: return@with

        val events = when (toDeviceValue) {
            is JSONArray -> toDeviceValue
            is JSONObject -> toDeviceValue.optJSONArray("events")
            else -> null
        }

        if (events == null || events.length() == 0) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: to_device exists but no events (count: ${events?.length() ?: 0})")
            }
            return@with
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Andromuks", "AppViewModel: Extracting ${events.length()} to_device events from sync_complete")
        }
        try {
            val payload = JSONObject().put("events", events)
            this@ToDeviceCoordinator.handleToDeviceMessage(payload)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w("Andromuks", "AppViewModel: Failed to parse to_device events from sync", e)
            }
        }
    }

    private fun normalizeToDevicePayload(data: Any?): Any? {
        return when (data) {
            is JSONObject -> {
                if (data.has("messages")) {
                    JSONObject().apply {
                        put("events", normalizeToDeviceMessages(data))
                    }
                } else if (data.has("events")) {
                    val normalizedEvents = normalizeToDeviceEvents(data.optJSONArray("events"))
                    JSONObject().apply {
                        put("events", normalizedEvents)
                    }
                } else {
                    data
                }
            }
            is JSONArray -> {
                JSONObject().apply {
                    put("events", normalizeToDeviceEvents(data))
                }
            }
            is Map<*, *> -> normalizeToDevicePayload(JSONObject(data))
            is List<*> -> normalizeToDevicePayload(JSONArray(data))
            else -> data
        }
    }

    private fun normalizeToDeviceEvents(rawEvents: JSONArray?): JSONArray {
        val normalized = JSONArray()
        if (rawEvents == null) return normalized
        for (i in 0 until rawEvents.length()) {
            val raw = rawEvents.optJSONObject(i) ?: continue
            normalized.put(normalizeToDeviceEvent(raw))
        }
        return normalized
    }

    private fun normalizeToDeviceMessages(raw: JSONObject): JSONArray {
        val normalized = JSONArray()
        val eventType = raw.optString("type")
        val sender = raw.optString("sender").takeIf { it.isNotBlank() }
        val messages = raw.optJSONObject("messages") ?: return normalized
        val userIds = messages.keys()
        while (userIds.hasNext()) {
            val userId = userIds.next()
            val devices = messages.optJSONObject(userId) ?: continue
            val deviceIds = devices.keys()
            while (deviceIds.hasNext()) {
                val deviceId = deviceIds.next()
                val content = devices.optJSONObject(deviceId) ?: continue
                val event = JSONObject()
                if (eventType.isNotBlank()) {
                    event.put("type", eventType)
                }
                if (sender != null) {
                    event.put("sender", sender)
                }
                event.put("content", content)
                event.put("to_user_id", userId)
                event.put("to_device_id", deviceId)
                normalized.put(event)
            }
        }
        return normalized
    }

    private fun normalizeToDeviceEvent(raw: JSONObject): JSONObject {
        val event = JSONObject()
        val decryptedType = raw.optString("decrypted_type").takeIf { it.isNotBlank() }
        if (decryptedType != null) {
            event.put("type", decryptedType)
        } else {
            raw.optString("type").takeIf { it.isNotBlank() }?.let { event.put("type", it) }
        }
        raw.optString("sender").takeIf { it.isNotBlank() }?.let { event.put("sender", it) }
        if (raw.has("content")) {
            event.put("content", raw.opt("content"))
        }
        if (decryptedType != null && raw.has("decrypted")) {
            event.put("content", raw.opt("decrypted"))
        }
        if (raw.has("encrypted")) {
            event.put("encrypted", raw.opt("encrypted"))
        }
        return event
    }
}
