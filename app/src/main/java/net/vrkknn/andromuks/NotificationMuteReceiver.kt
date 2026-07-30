package net.vrkknn.andromuks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import net.vrkknn.andromuks.utils.ExecApi
import net.vrkknn.andromuks.utils.RoomNotificationLevel
import kotlin.concurrent.thread

/**
 * Handles the notification's "Mute" action: silences the room by putting an empty-actions
 * room-scoped push rule, then dismisses the notification.
 *
 * Mirrors [NotificationMarkReadReceiver]'s dual-path structure — WebSocket via a registered
 * ViewModel when one exists, HTTP `/exec` in battery-saver mode where the socket is closed — because
 * the whole point of a notification action is that it works when the app is not running.
 *
 * Muting is deliberately the *only* room-management action offered here. It is reversible from
 * Settings, needs no user input, and matches what a notification surface is for: controlling noise.
 */
class NotificationMuteReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationMuteReceiver"
        const val ACTION_MUTE = "net.vrkknn.andromuks.ACTION_MUTE_ROOM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra("room_id") ?: return
        if (BuildConfig.DEBUG) Log.d(TAG, "Mute requested for room: $roomId")

        val prefs = context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val useBatterySaver = prefs.getBoolean("use_battery_saver_mode", false)

        if (useBatterySaver) {
            // BroadcastReceiver onReceive runs on the main thread and ExecApi is blocking, so the
            // call needs a worker thread; goAsync() keeps the receiver alive until it finishes.
            val pendingResult = goAsync()
            thread(name = "batterySaver-mute") {
                try {
                    val ok = ExecApi.muteRoom(ExecApi.readCredentials(context), roomId)
                    if (ok) {
                        Log.i(TAG, "Muted room $roomId over /exec")
                    } else {
                        Log.w(TAG, "Failed to mute room $roomId over /exec")
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        } else {
            val viewModel = WebSocketService.getRegisteredViewModels().firstOrNull()
            if (viewModel != null) {
                // Goes through the coordinator rather than a raw command so the in-memory ruleset is
                // updated too — otherwise the Settings UI would show the room as unmuted until the
                // next sync.
                viewModel.pushRulesCoordinator.setRoomNotificationLevel(roomId, RoomNotificationLevel.MUTE)
                Log.i(TAG, "Muted room $roomId via ViewModel")
            } else {
                // No ViewModel and not in battery-saver mode: the socket is down but the user still
                // tapped Mute. /exec is the only way to honour it, so use it rather than dropping
                // the action on the floor.
                val pendingResult = goAsync()
                thread(name = "mute-fallback") {
                    try {
                        val ok = ExecApi.muteRoom(ExecApi.readCredentials(context), roomId)
                        Log.i(TAG, "Muted room $roomId over /exec (no ViewModel): $ok")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }

        dismissNotification(context, roomId)
    }

    /**
     * Clears the room's notification. Also drops the MessagingStyle cache: the user has said they
     * do not want to hear from this room, so any buffered lines must not reappear if something
     * re-posts for it before the mute takes effect server-side.
     */
    private fun dismissNotification(context: Context, roomId: String) {
        if (BubbleTracker.isBubbleOpen(roomId)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Not dismissing notification for $roomId - bubble is open")
            return
        }
        try {
            val notifId = roomId.hashCode()
            NotificationManagerCompat.from(context).cancel(notifId)
            EnhancedNotificationDisplay.clearRoomMessageCache(roomId)
            EnhancedNotificationDisplay.refreshGroupSummary(context, justCancelledId = notifId)
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification after mute", e)
        }
    }
}
