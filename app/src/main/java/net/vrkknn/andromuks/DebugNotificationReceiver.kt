package net.vrkknn.andromuks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.vrkknn.andromuks.MainActivity
import net.vrkknn.andromuks.R
import net.vrkknn.andromuks.BuildConfig

/**
 * Debug-only receiver to post a messaging notification from inside the app process.
 *
 * Usage (ADB):
 * adb shell am broadcast -a net.vrkknn.andromuks.DEBUG_POST_NOTIFICATION \
 *   --es title "Matrix Test" --es body "Hello from AA"
 *
 * This ensures the notification is posted with the app UID, proper channel, and PUBLIC visibility.
 */
class DebugNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return

        Log.d(TAG, "DEBUG_POST_NOTIFICATION received")

        val title = intent.getStringExtra("title") ?: "Matrix Test"
        val body = intent.getStringExtra("body") ?: "Hello from AA"
        postTestNotification(context, title, body)
    }

    private fun postTestNotification(context: Context, title: String, body: String) {
        ensureChannel(context)

        val nmCompat = NotificationManagerCompat.from(context)
        val enabled = nmCompat.areNotificationsEnabled()
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nmCompat.getNotificationChannel(CHANNEL_ID)
        } else null
        Log.d(TAG, "Notifications enabled=$enabled channel=${channel?.id} importance=${channel?.importance}")

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Use a guaranteed-present system icon to avoid missing resource issues
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(999123, notification)
        showToast(context, "Debug notification posted (enabled=$enabled, importance=${channel?.importance ?: "n/a"})")
    }

    private fun showToast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Matrix Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Debug test notifications for Android Auto"
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "matrix_notifications"
        private const val TAG = "DebugNotificationReceiver"
    }
}

