package net.vrkknn.andromuks

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.IntelligentMediaCache

/**
 * Foreground service that carries the ongoing-call notification.
 *
 * Two jobs, and the second is the reason this is a service at all:
 *
 * 1. It posts a `CallStyle` notification, so a backgrounded call is reachable from anywhere —
 *    tapping it returns straight to the live [CallOverlay] WebView (nothing is reloaded or
 *    re-joined), and "Hang up" ends the call without navigating back into the room first.
 * 2. It declares the `microphone`/`camera` foreground service types. From Android 14 an app may
 *    only keep capturing while it is not visible if such a service is running, so this is what makes
 *    "backgrounded call" mean anything at all once the user leaves the app rather than just pressing
 *    Back. `CallStyle` also only keeps its system treatment (the status-bar chip) while it is a
 *    foreground service notification.
 *
 * The service is always started from a foreground Activity — Android forbids starting a
 * microphone/camera foreground service from the background — and the declared types are narrowed to
 * the permissions actually granted, because starting with a type whose permission is missing throws.
 */
// POST_NOTIFICATIONS is requested via the app's permission flow; posting without it is a silent
// no-op on API 33+ (no crash). Lint can't see the request, so the notify() calls below suppress the
// false positive, exactly as EnhancedNotificationDisplay does.
class CallForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var roomId: String = ""
    private var roomName: String = ""
    private var avatarUrl: String? = null
    private var connectedAt: Long = 0L
    private var started = false
    private var claimedTypes = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANGUP -> {
                Log.i(TAG, "Hang up requested from the call notification")
                // Read the handler *now*: tearing the service down clears it, and the posted lambda
                // would otherwise resolve a null field by the time the main thread ran it.
                val handler = hangupHandler
                hangupHandler = null
                Handler(Looper.getMainLooper()).post { handler?.invoke() }
                stopSelfCompat()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                stopSelfCompat()
                return START_NOT_STICKY
            }

            else -> {
                roomId = intent?.getStringExtra(EXTRA_ROOM_ID).orEmpty()
                roomName = intent?.getStringExtra(EXTRA_ROOM_NAME)?.takeIf { it.isNotBlank() } ?: "Call"
                avatarUrl = intent?.getStringExtra(EXTRA_AVATAR_URL)
                connectedAt = intent?.getLongExtra(EXTRA_CONNECTED_AT, 0L) ?: 0L
                postOrPromote(buildNotification(null))
                loadAvatarIcon()
            }
        }
        // The call lives in the Activity's WebView: if the process is restarted there is no call to
        // resume, so never let the system recreate this service on its own.
        return START_NOT_STICKY
    }

    /**
     * First call promotes us to the foreground; later ones only refresh the notification (the
     * "connecting" → "connected + chronometer" transition, and the avatar arriving).
     *
     * The exception is a *widened* type set. On a first-ever call the service starts before Element
     * Call has asked the user for the microphone, so it can only claim the types granted at that
     * moment — re-promoting once the permission lands is what actually buys the capture exemption.
     */
    @SuppressLint("MissingPermission")
    private fun postOrPromote(notification: Notification) {
        val types = grantedServiceTypes()
        if (started && types == claimedTypes) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            return
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            started = true
            claimedTypes = types
        } catch (e: SecurityException) {
            // The microphone/camera permission was revoked between the check above and here.
            degradeToPlainNotification(notification, e)
        } catch (e: IllegalStateException) {
            // A background start the system refused (Android 12+ FGS launch restrictions).
            degradeToPlainNotification(notification, e)
        }
    }

    /**
     * The call itself is unaffected by a failed promotion — keep a plain notification so the user can
     * still get back to it and hang up, and stop pretending to be a foreground service.
     */
    @SuppressLint("MissingPermission")
    private fun degradeToPlainNotification(notification: Notification, cause: Exception) {
        Log.w(TAG, "startForeground failed, falling back to a plain notification", cause)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        stopSelfCompat()
    }

    /**
     * The service declares `microphone|camera`, but a type may only be used while its permission is
     * granted — Element Call asks for the camera only on a video call, so the granted set is what we
     * actually claim.
     */
    private fun grantedServiceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var types = 0
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasPermission(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
    }

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(icon: IconCompat?): Notification {
        val person = Person.Builder()
            .setName(roomName)
            .setIcon(icon)
            .setImportant(true)
            .build()

        val returnIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_RETURN_TO_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROOM_ID, roomId)
        }
        val returnPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_RETURN,
            returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangupPendingIntent = PendingIntent.getService(
            this,
            REQUEST_HANGUP,
            Intent(this, CallForegroundService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangupPendingIntent))
            .setContentIntent(returnPendingIntent)
            .setContentText(if (connectedAt > 0L) "Ongoing call" else "Connecting…")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // The elapsed-time counter only starts once media is actually flowing, so it measures the
        // call rather than the time spent staring at Element Call's lobby.
        if (connectedAt > 0L) {
            builder.setWhen(connectedAt).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    /**
     * Cache-only avatar lookup, off the main thread: the notification is posted immediately without
     * one and refreshed if a cached file turns out to exist. Never fetches — a call notification
     * must not wait on the network.
     */
    @SuppressLint("MissingPermission")
    private fun loadAvatarIcon() {
        val url = avatarUrl?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            val icon = withContext(Dispatchers.IO) {
                try {
                    val file = IntelligentMediaCache.getCachedFile(this@CallForegroundService, url)
                        ?: return@withContext null
                    BitmapFactory.decodeFile(file.absolutePath)?.let(IconCompat::createWithAdaptiveBitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode the call notification avatar", e)
                    null
                }
            } ?: return@launch
            if (started) NotificationManagerCompat.from(this@CallForegroundService).notify(NOTIFICATION_ID, buildNotification(icon))
        }
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        started = false
        claimedTypes = 0
        stopSelf()
    }

    /**
     * The call only exists inside MainActivity's WebView, so a swiped-away task means the call is
     * gone — leaving the notification behind would offer a "return to call" that returns to nothing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        hangupHandler = null
        stopSelfCompat()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallForegroundService"
        private const val CHANNEL_ID = "ongoing_call"
        private const val NOTIFICATION_ID = 90210
        private const val REQUEST_RETURN = 1
        private const val REQUEST_HANGUP = 2

        private const val ACTION_START = "net.vrkknn.andromuks.CALL_START"
        private const val ACTION_HANGUP = "net.vrkknn.andromuks.CALL_HANGUP"
        private const val ACTION_STOP = "net.vrkknn.andromuks.CALL_STOP"

        /** Sent to [MainActivity] when the notification body is tapped. */
        const val ACTION_RETURN_TO_CALL = "net.vrkknn.andromuks.ACTION_RETURN_TO_CALL"
        const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_ROOM_NAME = "room_name"
        private const val EXTRA_AVATAR_URL = "avatar_url"
        private const val EXTRA_CONNECTED_AT = "connected_at"

        /**
         * Invoked on the main thread when the user taps "Hang up". Set while a call is active by
         * [CallsWidgetsCoordinator]; the service and the ViewModel that owns the call always live in
         * the same process, so a plain callback is enough.
         */
        @Volatile
        var hangupHandler: (() -> Unit)? = null

        /**
         * Show (or refresh) the call notification. Pass [connectedAt] as 0 while Element Call is
         * still connecting and as `System.currentTimeMillis()` once it is, which is when the
         * elapsed-time counter starts.
         */
        fun start(context: Context, roomId: String, roomName: String, avatarUrl: String?, connectedAt: Long) {
            ensureChannel(context)
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ROOM_NAME, roomName)
                putExtra(EXTRA_AVATAR_URL, avatarUrl)
                putExtra(EXTRA_CONNECTED_AT, connectedAt)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the call foreground service", e)
            }
        }

        fun stop(context: Context) {
            hangupHandler = null
            try {
                context.startService(Intent(context, CallForegroundService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop the call foreground service", e)
            }
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ongoing calls",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "The call currently in progress"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
