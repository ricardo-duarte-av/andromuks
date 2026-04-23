package net.vrkknn.andromuks

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache

/**
 * WorkManager worker that downloads a notification image after the initial
 * text-only notification has been posted and updates it in-place, silently.
 *
 * This two-phase approach mirrors what WhatsApp / Signal do:
 *   Phase 1 (FCM onMessageReceived): post notification immediately with body text.
 *   Phase 2 (this worker):           download image once network is confirmed
 *                                    available, then call notify() with the same
 *                                    notification ID to add the image.
 *
 * WorkManager jobs run outside Doze network restrictions (via JobScheduler
 * maintenance windows), which is why the download succeeds here but not in
 * onMessageReceived.
 */
class NotificationImageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NotifImageWorker"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_IMAGE_URL = "image_url"
        private const val KEY_MXC_URL = "mxc_url"
        private const val KEY_MIME_TYPE = "mime_type"
        private const val KEY_AUTH_TOKEN = "auth_token"

        /**
         * Enqueue an image-download job for the given notification.
         * Uses REPLACE policy so that rapid back-to-back image messages for the
         * same room don't pile up — only the latest image matters.
         */
        fun enqueue(
            context: Context,
            roomId: String,
            eventId: String?,
            imageUrl: String,
            mxcUrl: String?,
            mimeType: String,
            authToken: String
        ) {
            val data = Data.Builder()
                .putString(KEY_ROOM_ID, roomId)
                .putString(KEY_EVENT_ID, eventId)
                .putString(KEY_IMAGE_URL, imageUrl)
                .putString(KEY_MXC_URL, mxcUrl)
                .putString(KEY_MIME_TYPE, mimeType)
                .putString(KEY_AUTH_TOKEN, authToken)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<NotificationImageWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "notif_image_$roomId",
                ExistingWorkPolicy.REPLACE,
                request
            )

            if (BuildConfig.DEBUG) Log.d(TAG, "Enqueued image download for room $roomId: $imageUrl")
        }
    }

    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val eventId = inputData.getString(KEY_EVENT_ID)
        val imageUrl = inputData.getString(KEY_IMAGE_URL) ?: return Result.failure()
        val mxcUrl = inputData.getString(KEY_MXC_URL)
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "image/jpeg"
        val authToken = inputData.getString(KEY_AUTH_TOKEN) ?: return Result.failure()

        if (BuildConfig.DEBUG) Log.d(TAG, "Starting image download for room $roomId")

        // 1. Check if the notification is still active — user may have dismissed it already.
        val notifId = roomId.hashCode()
        val systemNm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = systemNm.activeNotifications.firstOrNull { it.id == notifId }
        if (existing == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Notification for room $roomId was dismissed — skipping image update")
            return Result.success()
        }

        // 2. Download the image. Checks disk cache first; only hits the network on a miss.
        val imageFile = try {
            if (mxcUrl != null) {
                IntelligentMediaCache.getCachedFile(applicationContext, mxcUrl)
                    ?: IntelligentMediaCache.downloadAndCache(applicationContext, mxcUrl, imageUrl, authToken)
            } else {
                // mxcUrl not available — use imageUrl as both cache key and download URL.
                IntelligentMediaCache.downloadAndCache(applicationContext, imageUrl, imageUrl, authToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image download failed for room $roomId", e)
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }

        if (imageFile == null || !imageFile.exists()) {
            Log.w(TAG, "Image file missing after download attempt for room $roomId")
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }

        // 3. Wrap in a content:// URI that the notification subsystem can read.
        val imageUri = try {
            FileProvider.getUriForFile(
                applicationContext,
                "pt.aguiarvieira.andromuks.fileprovider",
                imageFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider URI creation failed for room $roomId", e)
            return Result.failure()
        }

        listOf(
            "com.android.systemui",
            "com.google.android.projection.gearhead",
            applicationContext.packageName
        ).forEach { pkg ->
            try {
                applicationContext.grantUriPermission(pkg, imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }

        // 4. Extract the existing MessagingStyle so we can update it in-place.
        val existingStyle = MessagingStyle.extractMessagingStyleFromNotification(existing.notification)
        if (existingStyle == null) {
            Log.w(TAG, "Could not extract MessagingStyle for room $roomId")
            return Result.failure()
        }

        val messages = existingStyle.messages.toList()
        if (messages.isEmpty()) return Result.failure()

        // 5. Rebuild MessagingStyle: keep all messages, replace the last one with an image version.
        //    The last message is always the one we need to upgrade because the notification was
        //    just posted in Phase 1 with that message as text-only.
        val newStyle = NotificationCompat.MessagingStyle(existingStyle.user)
            .setConversationTitle(existingStyle.conversationTitle)
            .setGroupConversation(existingStyle.isGroupConversation)

        messages.dropLast(1).forEach { msg -> newStyle.addMessage(msg) }

        val last = messages.last()
        val imageMessage = MessagingStyle.Message(last.text, last.timestamp, last.person)
            .setData(mimeType, imageUri)
        newStyle.addMessage(imageMessage)

        // 6. Resolve channel ID from the active notification (API 26+) so we stay on the
        //    same channel and avoid triggering a sound/vibration on the update.
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            existing.notification.channelId
        } else {
            val sanitized = AvatarUtils.sanitizeIdForAndroid(roomId, maxLength = 50)
            "conversation_channel_$sanitized"
        }

        // 7. Rebuild and re-post silently — this is a visual-only update.
        val updatedNotification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.matrix)
            .setStyle(newStyle)
            .setContentIntent(existing.notification.contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setShortcutId(roomId)
            .apply {
                // Preserve event_id for any action handlers that need it.
                val storedEventId = existing.notification.extras?.getString("event_id") ?: eventId
                storedEventId?.let { addExtras(android.os.Bundle().apply { putString("event_id", it) }) }

                // Restore large icon from the original notification.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    existing.notification.getLargeIcon()?.let { icon ->
                        try {
                            val drawable = icon.loadDrawable(applicationContext)
                            if (drawable != null) {
                                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                drawable.setBounds(0, 0, w, h)
                                drawable.draw(canvas)
                                setLargeIcon(bmp)
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.w(TAG, "Could not restore large icon", e)
                        }
                    }
                }

                // Re-attach reply / mark-read actions from the original notification.
                // We copy the PendingIntents directly so the receivers still work.
                existing.notification.actions?.forEach { action ->
                    addAction(NotificationCompat.Action(0, action.title ?: "", action.actionIntent))
                }
            }
            .build()

        NotificationManagerCompat.from(applicationContext).notify(notifId, updatedNotification)

        if (BuildConfig.DEBUG) Log.d(TAG, "Notification updated with image for room $roomId")
        return Result.success()
    }
}
