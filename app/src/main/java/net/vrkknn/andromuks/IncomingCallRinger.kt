package net.vrkknn.andromuks

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat

/**
 * What the user chose on an incoming-call notification, waiting for the ViewModel to exist.
 *
 * A ring can arrive while the app is dead, so "Answer" may land before there is anything to answer
 * with. MainActivity parks the intent here and the Compose tree drains it once [AppViewModel] is up.
 */
sealed interface CallAction {
    /** The user tapped Answer: join the call in [roomId] as [callIntent] ("audio" or "video"). */
    data class Answer(val roomId: String, val callIntent: String) : CallAction

    /** The full-screen intent fired: show the in-app incoming-call banner for [info]. */
    data class Incoming(val info: IncomingCallInfo) : CallAction

    /**
     * A "Matrix call" row was tapped on an Android contact card: call [userId] in their canonical DM.
     *
     * Carries the person rather than a room because that tap routinely starts the process from
     * scratch — there is no ViewModel to resolve a room with, and no synced room list to resolve it
     * from, until well after the intent has been handled.
     */
    data class CallUser(val userId: String, val callIntent: String) : CallAction
}

/** Process-global hand-off for [CallAction], observed by MainActivity's composition. */
object PendingCallAction {
    var pending by mutableStateOf<CallAction?>(null)
        private set

    fun offer(action: CallAction) {
        pending = action
    }

    fun consume(): CallAction? {
        val action = pending
        pending = null
        return action
    }
}

/**
 * The incoming-call ring: a `CallStyle` notification with Answer/Decline and a full-screen intent.
 *
 * This is the backgrounded half of incoming calls. While the app is alive and syncing,
 * `org.matrix.msc4075.rtc.notification` arrives over the WebSocket and
 * [CallsWidgetsCoordinator.handleRtcNotification] shows [IncomingCallBanner]; when it is not, the
 * same event has to arrive as a high-priority push instead (see FCMService.handleCallNotification).
 *
 * Ringing rather than merely notifying needs three things beyond an ordinary notification:
 * a channel whose sound is the device ringtone under `USAGE_NOTIFICATION_RINGTONE`, a
 * `CallStyle.forIncomingCall` so the system renders call affordances, and a full-screen intent so it
 * takes over the screen when locked. The last one is a privilege: from Android 14 apps that are not
 * calling or alarm apps have `USE_FULL_SCREEN_INTENT` denied by default, so the notification is
 * built to degrade to a heads-up ring when [canRing] is false rather than to fail.
 */
object IncomingCallRinger {
    private const val TAG = "IncomingCallRinger"
    private const val CHANNEL_ID = "incoming_call"
    private const val STARTED_CHANNEL_ID = "call_started"
    private const val NOTIFICATION_ID = 90211

    const val ACTION_ANSWER = "net.vrkknn.andromuks.CALL_ANSWER"
    const val ACTION_DECLINE = "net.vrkknn.andromuks.CALL_DECLINE"
    const val ACTION_INCOMING = "net.vrkknn.andromuks.CALL_INCOMING"

    const val EXTRA_ROOM_ID = "room_id"
    const val EXTRA_CALLER_ID = "caller_id"
    const val EXTRA_CALL_INTENT = "call_intent"
    const val EXTRA_EXPIRES_AT = "expires_at"

    private const val REQUEST_ANSWER = 10
    private const val REQUEST_DECLINE = 11
    private const val REQUEST_FULL_SCREEN = 12

    private val VIBRATION_PATTERN = longArrayOf(0, 1000, 1000)

    /**
     * Whether the system will let a full-screen intent actually take over the screen. False means
     * the ring still posts, as a heads-up notification — degraded, not broken.
     */
    fun canRing(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.canUseFullScreenIntent()
    }

    @SuppressLint("MissingPermission")
    fun ring(context: Context, roomId: String, roomName: String, caller: String, text: String, callIntent: String, expiresAt: Long, icon: IconCompat? = null) {
        ensureChannel(context)
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0L) {
            Log.i(TAG, "Ignoring an incoming call that already expired")
            return
        }

        val person = Person.Builder().setName(caller).setIcon(icon).setImportant(true).build()
        val answer = activityIntent(context, ACTION_ANSWER, REQUEST_ANSWER, roomId, caller, callIntent, expiresAt)
        val fullScreen = activityIntent(context, ACTION_INCOMING, REQUEST_FULL_SCREEN, roomId, caller, callIntent, expiresAt)
        val decline = PendingIntent.getBroadcast(
            context,
            REQUEST_DECLINE,
            Intent(context, CallActionReceiver::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setContentText(if (text.isNotEmpty()) "$text in $roomName" else "Incoming call in $roomName")
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            // The ring is only valid for the event's lifetime; let the system retire it even if the
            // process is gone by then, so a missed call cannot ring forever.
            .setTimeoutAfter(remaining)
            .build()

        Log.i(TAG, "Ringing for $roomId (fullScreenIntent allowed=${canRing(context)}, ${remaining}ms left)")
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * `notification_type: "notification"` — someone started a call, but this is not a summons: no
     * ring, no full-screen intent, no `CallStyle`. Tapping opens the room; "Join call" joins it.
     * One notification per room, so a second announcement for the same room replaces the first.
     */
    @SuppressLint("MissingPermission")
    fun notifyCallStarted(
        context: Context,
        roomId: String,
        roomName: String,
        caller: String,
        text: String,
        callIntent: String,
        expiresAt: Long,
        icon: IconCompat? = null,
    ) {
        ensureStartedChannel(context)
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0L) {
            Log.i(TAG, "Ignoring a call announcement that already expired")
            return
        }

        val open = PendingIntent.getActivity(
            context,
            roomId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("room_id", roomId)
                putExtra("direct_navigation", true)
                putExtra("from_notification", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val join = activityIntent(context, ACTION_ANSWER, roomId.hashCode() + 1, roomId, caller, callIntent, expiresAt)

        val notification = NotificationCompat.Builder(context, STARTED_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(roomName)
            .setContentText(if (caller.isNotEmpty() && caller != roomName) "$caller: $text" else text)
            .setContentIntent(open)
            .also { builder -> icon?.let { builder.setLargeIcon(it.toIcon(context)) } }
            .addAction(0, if (callIntent == "audio") "Join with voice" else "Join call", join)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(remaining)
            .build()

        Log.i(TAG, "Announcing a call started in $roomId (${remaining}ms left)")
        NotificationManagerCompat.from(context).notify(roomId.hashCode(), notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun activityIntent(
        context: Context,
        action: String,
        requestCode: Int,
        roomId: String,
        caller: String,
        callIntent: String,
        expiresAt: Long,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_CALLER_ID, caller)
            putExtra(EXTRA_CALL_INTENT, callIntent)
            putExtra(EXTRA_EXPIRES_AT, expiresAt)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureStartedChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(STARTED_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                STARTED_CHANNEL_ID,
                "Calls started",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Someone started a call in a room"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Rings when someone starts a call"
            setShowBadge(false)
            enableLights(true)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // USAGE_NOTIFICATION_RINGTONE is what makes this play at ring volume and follow the
            // ringer mode, rather than being treated as a notification blip.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }
}
