package net.vrkknn.andromuks

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UI prefs and SharedPreferences-backed settings — see [AppViewModel] toggles / [loadSettings].
 */
internal class SettingsCoordinator(private val vm: AppViewModel) {

    fun toggleCompression() = with(vm) {
        enableCompression = !enableCompression

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val saved = prefs.edit()
                .putBoolean("enable_compression", enableCompression)
                .commit()

            val verify = prefs.getBoolean("enable_compression", !enableCompression)
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Saved enableCompression setting: $enableCompression (commit result: $saved, verified: $verify)")
                if (verify != enableCompression) {
                    android.util.Log.e("Andromuks", "AppViewModel: CRITICAL - Compression setting mismatch! Expected: $enableCompression, got: $verify")
                }
            }

            vm.viewModelScope.launch {
                delay(100)

                val finalValue = prefs.getBoolean("enable_compression", !enableCompression)
                if (finalValue != enableCompression) {
                    android.util.Log.e("Andromuks", "AppViewModel: Compression setting still incorrect after delay! Expected: $enableCompression, got: $finalValue - retrying save")
                    prefs.edit().putBoolean("enable_compression", enableCompression).commit()
                }

                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Restarting WebSocket due to compression setting change (value: $enableCompression)")
                logActivity("Compression Setting Changed - Restarting", null)
                restartWebSocket(ReconnectTrigger.Unclassified("Compression setting changed"))
            }
        } ?: run {
            android.util.Log.e("Andromuks", "AppViewModel: Cannot toggle compression - appContext is null")
        }
    }

    fun toggleEnterKeyBehavior() = with(vm) {
        enterKeySendsMessage = !enterKeySendsMessage

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("enter_key_sends_message", enterKeySendsMessage)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "AppViewModel: Saved enterKeySendsMessage setting: $enterKeySendsMessage")
        }
    }

    fun toggleLoadThumbnailsIfAvailable() = with(vm) {
        loadThumbnailsIfAvailable = !loadThumbnailsIfAvailable

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("load_thumbnails_if_available", loadThumbnailsIfAvailable)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved loadThumbnailsIfAvailable setting: $loadThumbnailsIfAvailable"
            )
        }
    }

    fun toggleRenderThumbnailsAlways() = with(vm) {
        renderThumbnailsAlways = !renderThumbnailsAlways

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("render_thumbnails_always", renderThumbnailsAlways)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved renderThumbnailsAlways setting: $renderThumbnailsAlways"
            )
        }
    }

    fun toggleShowAllRoomListTabs() = with(vm) {
        showAllRoomListTabs = !showAllRoomListTabs

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("show_all_room_list_tabs", showAllRoomListTabs)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved showAllRoomListTabs setting: $showAllRoomListTabs"
            )
        }
    }

    fun toggleMoveReadReceiptsToEdge() = with(vm) {
        moveReadReceiptsToEdge = !moveReadReceiptsToEdge

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("move_read_receipts_to_edge", moveReadReceiptsToEdge)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved moveReadReceiptsToEdge setting: $moveReadReceiptsToEdge"
            )
        }
    }

    fun toggleTrimLongDisplayNames() = with(vm) {
        trimLongDisplayNames = !trimLongDisplayNames

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("trim_long_display_names", trimLongDisplayNames)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved trimLongDisplayNames setting: $trimLongDisplayNames"
            )
        }
    }

    fun toggleShowLinkPreviews() = with(vm) {
        showLinkPreviews = !showLinkPreviews

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("show_link_previews", showLinkPreviews)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved showLinkPreviews setting: $showLinkPreviews"
            )
        }
    }

    fun toggleSendLinkPreviews() = with(vm) {
        sendLinkPreviews = !sendLinkPreviews

        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("send_link_previews", sendLinkPreviews)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved sendLinkPreviews setting: $sendLinkPreviews"
            )
        }
    }

    fun updateElementCallBaseUrl(url: String) = with(vm) {
        elementCallBaseUrl = url.trim()
        appContext?.let { context ->
            val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("element_call_base_url", elementCallBaseUrl)
                .apply()
            if (BuildConfig.DEBUG) android.util.Log.d(
                "Andromuks",
                "AppViewModel: Saved elementCallBaseUrl setting: $elementCallBaseUrl"
            )
        }
    }

    fun updateBackgroundPurgeInterval(minutes: Int) = with(vm) {
        val clamped = minutes.coerceIn(1, 1440)
        backgroundPurgeIntervalMinutes = clamped
        syncBatchProcessor.batchIntervalMs = clamped.toLong() * 60_000L
        appContext?.let { ctx ->
            ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("background_purge_interval_minutes", clamped)
                .apply()
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks",
            "AppViewModel: backgroundPurgeIntervalMinutes=$clamped → ${syncBatchProcessor.batchIntervalMs}ms")
    }

    fun updateBackgroundPurgeThreshold(count: Int) = with(vm) {
        val clamped = count.coerceIn(10, 100_000)
        backgroundPurgeMessageThreshold = clamped
        syncBatchProcessor.maxBatchSize = clamped
        appContext?.let { ctx ->
            ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("background_purge_message_threshold", clamped)
                .apply()
        }
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks",
            "AppViewModel: backgroundPurgeMessageThreshold=$clamped")
    }



    private fun booleanPrefOrNull(prefs: android.content.SharedPreferences, key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    fun setDeviceGlobalShowMediaPreviews(value: Boolean?) = with(vm) {
        deviceGlobalShowMediaPreviews = value
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (value == null) editor.remove("gomuks_device_show_media_previews")
            else editor.putBoolean("gomuks_device_show_media_previews", value)
            editor.apply()
        }
    }

    fun getDeviceRoomShowMediaPreviews(roomId: String): Boolean? = with(vm) {
        val ctx = appContext ?: return null
        val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        return booleanPrefOrNull(prefs, "gomuks_room_show_media_previews_$roomId")
    }

    fun setDeviceRoomShowMediaPreviews(roomId: String, value: Boolean?) = with(vm) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val key = "gomuks_room_show_media_previews_$roomId"
            if (value == null) editor.remove(key) else editor.putBoolean(key, value)
            editor.apply()
        }
        gomuksRoomPrefsVersion++
    }

    fun setDeviceGlobalRenderUrlPreviews(value: Boolean?) = with(vm) {
        deviceGlobalRenderUrlPreviews = value
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (value == null) editor.remove("gomuks_device_render_url_previews")
            else editor.putBoolean("gomuks_device_render_url_previews", value)
            editor.apply()
        }
    }

    fun getDeviceRoomRenderUrlPreviews(roomId: String): Boolean? = with(vm) {
        val ctx = appContext ?: return null
        val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        return booleanPrefOrNull(prefs, "gomuks_room_render_url_previews_$roomId")
    }

    fun setDeviceRoomRenderUrlPreviews(roomId: String, value: Boolean?) = with(vm) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val key = "gomuks_room_render_url_previews_$roomId"
            if (value == null) editor.remove(key) else editor.putBoolean(key, value)
            editor.apply()
        }
        gomuksRoomPrefsVersion++
    }

    fun setDeviceGlobalSendBundledUrlPreviews(value: Boolean?) = with(vm) {
        deviceGlobalSendBundledUrlPreviews = value
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (value == null) editor.remove("gomuks_device_send_bundled_url_previews")
            else editor.putBoolean("gomuks_device_send_bundled_url_previews", value)
            editor.apply()
        }
    }

    fun getDeviceRoomSendBundledUrlPreviews(roomId: String): Boolean? = with(vm) {
        val ctx = appContext ?: return null
        val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        return booleanPrefOrNull(prefs, "gomuks_room_send_bundled_url_previews_$roomId")
    }

    fun setDeviceRoomSendBundledUrlPreviews(roomId: String, value: Boolean?) = with(vm) {
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val key = "gomuks_room_send_bundled_url_previews_$roomId"
            if (value == null) editor.remove(key) else editor.putBoolean(key, value)
            editor.apply()
        }
        gomuksRoomPrefsVersion++
    }

    fun loadSettings(context: Context? = null) = with(vm) {
        val contextToUse = context ?: appContext
        contextToUse?.let { ctx ->
            val prefs = ctx.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
            enableCompression = prefs.getBoolean("enable_compression", false)
            enterKeySendsMessage = prefs.getBoolean("enter_key_sends_message", true)
            loadThumbnailsIfAvailable = prefs.getBoolean("load_thumbnails_if_available", true)
            renderThumbnailsAlways = prefs.getBoolean("render_thumbnails_always", true)
            showAllRoomListTabs = prefs.getBoolean("show_all_room_list_tabs", false)
            moveReadReceiptsToEdge = prefs.getBoolean("move_read_receipts_to_edge", false)
            trimLongDisplayNames = prefs.getBoolean("trim_long_display_names", true)
            showLinkPreviews = prefs.getBoolean("show_link_previews", true)
            sendLinkPreviews = prefs.getBoolean("send_link_previews", true)
            elementCallBaseUrl = prefs.getString("element_call_base_url", "") ?: ""
            deviceGlobalShowMediaPreviews = booleanPrefOrNull(prefs, "gomuks_device_show_media_previews")
            deviceGlobalRenderUrlPreviews = booleanPrefOrNull(prefs, "gomuks_device_render_url_previews")
            deviceGlobalSendBundledUrlPreviews = booleanPrefOrNull(prefs, "gomuks_device_send_bundled_url_previews")

            val defaultIntervalMin = (SyncBatchProcessor.DEFAULT_BATCH_INTERVAL_MS / 60_000L).toInt()
            backgroundPurgeIntervalMinutes = prefs.getInt("background_purge_interval_minutes", defaultIntervalMin)
            backgroundPurgeMessageThreshold = prefs.getInt("background_purge_message_threshold", SyncBatchProcessor.DEFAULT_MAX_BATCH_SIZE)
            syncBatchProcessor.batchIntervalMs = backgroundPurgeIntervalMinutes.toLong() * 60_000L
            syncBatchProcessor.maxBatchSize = backgroundPurgeMessageThreshold

            if (BuildConfig.DEBUG) {
                android.util.Log.d("Andromuks", "AppViewModel: Loaded enableCompression setting: $enableCompression")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded enterKeySendsMessage setting: $enterKeySendsMessage")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded loadThumbnailsIfAvailable setting: $loadThumbnailsIfAvailable")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded renderThumbnailsAlways setting: $renderThumbnailsAlways")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded showAllRoomListTabs setting: $showAllRoomListTabs")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded moveReadReceiptsToEdge setting: $moveReadReceiptsToEdge")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded trimLongDisplayNames setting: $trimLongDisplayNames")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded showLinkPreviews setting: $showLinkPreviews")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded sendLinkPreviews setting: $sendLinkPreviews")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded elementCallBaseUrl setting: $elementCallBaseUrl")
                android.util.Log.d("Andromuks", "AppViewModel: Loaded backgroundPurgeIntervalMinutes=$backgroundPurgeIntervalMinutes, backgroundPurgeMessageThreshold=$backgroundPurgeMessageThreshold")
            }
        }
    }
}
