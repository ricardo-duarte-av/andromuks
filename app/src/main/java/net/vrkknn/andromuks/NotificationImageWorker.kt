package net.vrkknn.andromuks

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.IconCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache

/**
 * WorkManager worker that finishes a notification after the initial text-only / lettermark
 * notification has been posted, then updates it in-place, silently.
 *
 * It handles BOTH deferred media kinds in a single pass:
 *   - the message **image** (downloaded + `setData` on the target message), and
 *   - the **avatars** (room / sender / current-user) that were a cache miss in Phase 1.
 *
 * This is a single worker on purpose. Avatars and media are both `mxc://`-keyed, so they share
 * one download primitive ([IntelligentMediaCache]). More importantly, having one worker do one
 * read of the active notification, one rebuild, and one `notify()` makes a clobbering re-post
 * race **structurally impossible** — there is exactly one writer. Two independent workers (the
 * old image + avatar split) could read the same pre-update notification concurrently and the
 * second `notify()` would wipe the first's contribution.
 *
 * Two-phase delivery (mirrors what WhatsApp / Signal do):
 *   Phase 1 ([EnhancedNotificationDisplay.showEnhancedNotification]): post immediately with body
 *           text and whatever avatars were already cached (lettermarks otherwise).
 *   Phase 2 (this worker): download the image and the missing avatars once network is confirmed
 *           available (WorkManager runs outside Doze network restrictions, unlike the FCM
 *           callback's ~20 s wake budget — see docs/NOTIFICATIONS.md), then re-post in-place.
 */
class NotificationImageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NotifMediaWorker"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_IS_GROUP = "is_group"
        private const val KEY_SENDER_ID = "sender_id"
        private const val KEY_IMAGE_URL = "image_url"
        private const val KEY_MXC_URL = "mxc_url"
        private const val KEY_MIME_TYPE = "mime_type"
        private const val KEY_MESSAGE_TIMESTAMP = "message_timestamp"
        private const val KEY_ROOM_AVATAR_MXC = "room_avatar_mxc"
        private const val KEY_SENDER_AVATAR_MXC = "sender_avatar_mxc"
        private const val KEY_ME_AVATAR_MXC = "me_avatar_mxc"
        private const val KEY_IMAGE_AUTH_TOKEN = "image_auth_token"
        private const val KEY_AUTH_TOKEN = "auth_token"

        /**
         * Enqueue a Phase-2 update for the given notification. Every media parameter is optional —
         * pass only what was actually deferred:
         *  - [imageUrl]/[mxcUrl]/[mimeType] for a message image (null for text messages), and
         *  - [roomAvatarMxc]/[senderAvatarMxc]/[meAvatarMxc] for avatars that missed the cache.
         *
         * Per room+event work name with KEEP so a duplicate FCM delivery for the same event does
         * not double-enqueue, while a newer message in the same room gets its own job.
         */
        fun enqueue(
            context: Context,
            roomId: String,
            eventId: String?,
            isGroupRoom: Boolean,
            senderId: String,
            imageUrl: String?,
            mxcUrl: String?,
            mimeType: String?,
            messageTimestamp: Long,
            roomAvatarMxc: String?,
            senderAvatarMxc: String?,
            meAvatarMxc: String?,
            imageAuthToken: String,
            authToken: String
        ) {
            // Nothing deferred → nothing to do.
            if (imageUrl == null && roomAvatarMxc == null && senderAvatarMxc == null && meAvatarMxc == null) return

            val data = Data.Builder()
                .putString(KEY_ROOM_ID, roomId)
                .putString(KEY_EVENT_ID, eventId)
                .putBoolean(KEY_IS_GROUP, isGroupRoom)
                .putString(KEY_SENDER_ID, senderId)
                .putString(KEY_IMAGE_URL, imageUrl)
                .putString(KEY_MXC_URL, mxcUrl)
                .putString(KEY_MIME_TYPE, mimeType)
                .putLong(KEY_MESSAGE_TIMESTAMP, messageTimestamp)
                .putString(KEY_ROOM_AVATAR_MXC, roomAvatarMxc)
                .putString(KEY_SENDER_AVATAR_MXC, senderAvatarMxc)
                .putString(KEY_ME_AVATAR_MXC, meAvatarMxc)
                .putString(KEY_IMAGE_AUTH_TOKEN, imageAuthToken)
                .putString(KEY_AUTH_TOKEN, authToken)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<NotificationImageWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                // Run as expedited so the job isn't deferred into a distant Doze window.
                // KEEP falls through to a normal-priority job on devices with no expedited quota.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            val workName = if (eventId != null) "notif_media_${roomId}_$eventId" else "notif_media_$roomId"
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.KEEP,
                request
            )

            if (BuildConfig.DEBUG) Log.d(
                TAG,
                "Enqueued media update for room $roomId (image=${imageUrl != null}, roomAvatar=${roomAvatarMxc != null}, senderAvatar=${senderAvatarMxc != null}, meAvatar=${meAvatarMxc != null})"
            )
        }
    }

    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val eventId = inputData.getString(KEY_EVENT_ID)
        val isGroupRoom = inputData.getBoolean(KEY_IS_GROUP, false)
        val senderId = inputData.getString(KEY_SENDER_ID) ?: ""
        val imageUrl = inputData.getString(KEY_IMAGE_URL)
        val mxcUrl = inputData.getString(KEY_MXC_URL)
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "image/jpeg"
        val messageTimestamp = inputData.getLong(KEY_MESSAGE_TIMESTAMP, 0L)
        val roomAvatarMxc = inputData.getString(KEY_ROOM_AVATAR_MXC)
        val senderAvatarMxc = inputData.getString(KEY_SENDER_AVATAR_MXC)
        val meAvatarMxc = inputData.getString(KEY_ME_AVATAR_MXC)

        // Re-read auth token at run time so a delayed job uses a fresh credential.
        // Falls back to the token captured at enqueue time if SharedPreferences is empty.
        val sharedPrefs = applicationContext.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
        val freshToken = sharedPrefs.getString("image_auth_token", "")
            ?.takeIf { it.isNotBlank() }
            ?: sharedPrefs.getString("gomuks_auth_token", "")
            ?: ""
        val batchToken = inputData.getString(KEY_IMAGE_AUTH_TOKEN) ?: ""
        val authToken = freshToken.ifBlank { inputData.getString(KEY_AUTH_TOKEN) ?: "" }

        if (BuildConfig.DEBUG) Log.d(TAG, "Starting media update for room $roomId")

        // 1. Bail if the notification was dismissed / marked read before we ran.
        val notifId = roomId.hashCode()
        val systemNm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = systemNm.activeNotifications.firstOrNull { it.id == notifId }
        if (existing == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Notification for room $roomId was dismissed — skipping media update")
            Androlog(
                "Notifications",
                "Room $roomId: media update skipped — notification no longer active before worker ran. eventId=$eventId"
            )
            return Result.success()
        }

        // 2. Download everything that was deferred, in parallel. Avatars and the image are all
        //    cache-first. The batch token is preferred for media auth; the session token is the
        //    fallback. The image URL already carries ?encrypted=...&image_auth=... from Phase 1.
        val effectiveToken = batchToken.ifBlank { authToken }
        val maxPx = (48f * applicationContext.resources.displayMetrics.density).toInt().coerceAtLeast(96)

        data class Downloads(
            val image: File?,
            val room: Bitmap?,
            val sender: Bitmap?,
            val me: Bitmap?
        )
        val dl = coroutineScope {
            val imageDeferred = async {
                if (imageUrl == null) null
                else downloadImageFile(mxcUrl, imageUrl, authToken)
            }
            val roomDeferred = async { roomAvatarMxc?.let { loadCircular(it, homeserverUrl, effectiveToken, authToken, maxPx) } }
            val senderDeferred = async { senderAvatarMxc?.let { loadCircular(it, homeserverUrl, effectiveToken, authToken, maxPx) } }
            val meDeferred = async { meAvatarMxc?.let { loadCircular(it, homeserverUrl, effectiveToken, authToken, maxPx) } }
            Downloads(imageDeferred.await(), roomDeferred.await(), senderDeferred.await(), meDeferred.await())
        }

        val imageRequested = imageUrl != null
        // 3. Wrap a downloaded image in a content:// URI the notification subsystem can read.
        val imageUri = dl.image?.let { file ->
            try {
                val uri = FileProvider.getUriForFile(applicationContext, "pt.aguiarvieira.andromuks.fileprovider", file)
                listOf("com.android.systemui", "com.google.android.projection.gearhead", applicationContext.packageName)
                    .forEach { pkg ->
                        try {
                            applicationContext.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) { }
                    }
                uri
            } catch (e: Exception) {
                Log.e(TAG, "FileProvider URI creation failed for room $roomId", e)
                null
            }
        }

        val senderIcon = dl.sender?.let { IconCompat.createWithAdaptiveBitmap(it) }
        val meIcon = dl.me?.let { IconCompat.createWithBitmap(it) }

        val anyApplied = imageUri != null || senderIcon != null || meIcon != null || dl.room != null
        if (!anyApplied) {
            // Nothing downloaded → don't touch the notification. Retry transient failures.
            val willRetry = runAttemptCount < 3
            Androlog(
                "Notifications",
                "Room $roomId: Phase-2 produced nothing on attempt ${runAttemptCount + 1} " +
                    "(image=$imageRequested, avatars=${roomAvatarMxc != null || senderAvatarMxc != null || meAvatarMxc != null}). " +
                    (if (willRetry) "Will retry." else "Giving up; notification keeps its Phase-1 content.")
            )
            return if (willRetry) Result.retry() else Result.success()
        }

        // 4. Extract the live MessagingStyle to patch in-place.
        val existingStyle = MessagingStyle.extractMessagingStyleFromNotification(existing.notification)
        if (existingStyle == null) {
            Log.w(TAG, "Could not extract MessagingStyle for room $roomId")
            return Result.failure()
        }
        val messages = existingStyle.messages.toList()
        if (messages.isEmpty()) {
            Androlog("Notifications", "Room $roomId: active notification had no messages to upgrade.")
            return Result.failure()
        }

        // Target message for the image: match by timestamp so a newer text message that arrived
        // between Phase 1 and now is not overwritten with image data. Falls back to the last.
        val imageTargetIndex = if (imageUri != null) {
            if (messageTimestamp > 0L) {
                messages.indexOfLast { it.timestamp == messageTimestamp }.takeIf { it >= 0 } ?: messages.lastIndex
            } else {
                messages.lastIndex
            }
        } else {
            -1
        }

        // 5. Rebuild: patch "me" Person icon (root), each message's sender Person icon, and the
        //    target message's image data — all in one pass.
        val newUser = if (meIcon != null) rebuildPerson(existingStyle.user, meIcon) else existingStyle.user
        val newStyle = MessagingStyle(newUser)
            .setConversationTitle(existingStyle.conversationTitle)
            .setGroupConversation(existingStyle.isGroupConversation)
        messages.forEachIndexed { idx, msg ->
            val person = msg.person
            val updatePerson = senderIcon != null && person != null && person.key == senderId
            val applyImage = imageUri != null && idx == imageTargetIndex
            if (updatePerson || applyImage) {
                val newPerson = if (updatePerson) rebuildPerson(person!!, senderIcon!!) else person
                val rebuilt = MessagingStyle.Message(msg.text, msg.timestamp, newPerson)
                if (applyImage) {
                    rebuilt.setData(mimeType, imageUri!!)
                } else {
                    val mime = msg.dataMimeType
                    val uri = msg.dataUri
                    if (mime != null && uri != null) rebuilt.setData(mime, uri)
                }
                newStyle.addMessage(rebuilt)
            } else {
                newStyle.addMessage(msg)
            }
        }

        // 6. Channel: stay on the notification's existing channel so the re-post doesn't re-alert.
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            existing.notification.channelId
        } else {
            val sanitized = AvatarUtils.sanitizeIdForAndroid(roomId, maxLength = 50)
            "conversation_channel_$sanitized"
        }

        // 7. Large icon: groups show the room avatar, DMs the sender avatar. Use the freshly
        //    downloaded bitmap if we have it; otherwise restore the existing one so we don't blank it.
        val newLargeIcon: Bitmap? = if (isGroupRoom) dl.room else dl.sender

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.matrix)
            .setStyle(newStyle)
            .setContentIntent(existing.notification.contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            // Keep this child in the shared group; GROUP_ALERT_CHILDREN matches the Phase 1 post.
            .setGroup(EnhancedNotificationDisplay.NOTIFICATION_GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setShortcutId(roomId)
            .setWhen(existing.notification.`when`)

        if (newLargeIcon != null) {
            builder.setLargeIcon(newLargeIcon)
        } else {
            restoreLargeIcon(existing.notification)?.let { builder.setLargeIcon(it) }
        }

        // Preserve conversation linkage and bubble so a media update never drops them.
        NotificationCompat.getLocusId(existing.notification)?.let { builder.setLocusId(it) }
        NotificationCompat.getBubbleMetadata(existing.notification)?.let { builder.setBubbleMetadata(it) }

        // Preserve event_id extra and reply / mark-read actions.
        val storedEventId = existing.notification.extras?.getString("event_id") ?: eventId
        storedEventId?.let { builder.addExtras(android.os.Bundle().apply { putString("event_id", it) }) }
        existing.notification.actions?.forEach { action ->
            builder.addAction(NotificationCompat.Action(0, action.title ?: "", action.actionIntent))
        }

        NotificationManagerCompat.from(applicationContext).notify(notifId, builder.build())
        EnhancedNotificationDisplay.refreshGroupSummary(applicationContext, justPostedChild = true)

        // 8. Keep the in-memory MessagingStyle cache consistent so a later rebuild (new message in
        //    the room) keeps the image/avatars instead of reverting to the Phase-1 placeholder.
        if (imageUri != null && messageTimestamp > 0L) {
            EnhancedNotificationDisplay.upgradeMessageToImage(roomId, messageTimestamp, mimeType, imageUri)
        }
        if (senderIcon != null) {
            EnhancedNotificationDisplay.upgradeAvatarsInCache(roomId, senderId, senderIcon)
        }

        if (BuildConfig.DEBUG) Log.d(
            TAG,
            "Notification updated for room $roomId (image=${imageUri != null}, room=${dl.room != null}, sender=${dl.sender != null}, me=${dl.me != null})"
        )
        Androlog(
            "Notifications",
            "Room $roomId: notification updated (image=${imageUri != null}, room=${dl.room != null}, sender=${dl.sender != null}, me=${dl.me != null})."
        )

        // If an image was requested but we couldn't get it (avatars may still have posted), retry
        // so the image lands on a later attempt; avatar-only failures are not retried here.
        return if (imageRequested && imageUri == null && runAttemptCount < 3) Result.retry() else Result.success()
    }

    /** Download (cache-first) the message image to a file, or null on failure. */
    private suspend fun downloadImageFile(
        mxcUrl: String?,
        imageUrl: String,
        authToken: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (mxcUrl != null) {
                IntelligentMediaCache.getCachedFile(applicationContext, mxcUrl)
                    ?: IntelligentMediaCache.downloadAndCache(applicationContext, mxcUrl, imageUrl, authToken)
            } else {
                IntelligentMediaCache.downloadAndCache(applicationContext, imageUrl, imageUrl, authToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image download failed", e)
            null
        }
    }

    /** Download (cache-first) an avatar and return a circular, downscaled bitmap, or null. */
    private suspend fun loadCircular(
        mxcUrl: String,
        homeserverUrl: String,
        effectiveToken: String,
        authToken: String,
        maxPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = IntelligentMediaCache.getCachedFile(applicationContext, mxcUrl)
                ?: run {
                    if (homeserverUrl.isBlank()) return@withContext null
                    val baseHttpUrl = when {
                        mxcUrl.startsWith("mxc://") -> AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl)
                        mxcUrl.startsWith("_gomuks/") -> "$homeserverUrl/$mxcUrl"
                        else -> mxcUrl
                    } ?: return@withContext null
                    val httpUrl = if (effectiveToken.isNotEmpty() && baseHttpUrl.contains("/_gomuks/media/")) {
                        val sep = if (baseHttpUrl.contains("?")) "&" else "?"
                        "$baseHttpUrl${sep}image_auth=$effectiveToken"
                    } else {
                        baseHttpUrl
                    }
                    IntelligentMediaCache.downloadAndCache(applicationContext, mxcUrl, httpUrl, authToken)
                }
            val bitmap = file?.let { decodeScaledBitmap(it, maxPx) } ?: return@withContext null
            createCircularBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "loadCircular failed for $mxcUrl", e)
            null
        }
    }

    private fun rebuildPerson(person: Person, icon: IconCompat): Person =
        Person.Builder()
            .setName(person.name)
            .setKey(person.key)
            .setUri(person.uri)
            .setIcon(icon)
            .build()

    /** Render the active notification's large icon back to a Bitmap so we can re-set it. */
    private fun restoreLargeIcon(notification: android.app.Notification): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val icon = notification.getLargeIcon() ?: return null
            val drawable = icon.loadDrawable(applicationContext) ?: return null
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Could not restore large icon", e)
            null
        }
    }

    // The two helpers below mirror EnhancedNotificationDisplay's private versions (kept local so
    // this worker has no dependency on a live EnhancedNotificationDisplay instance).

    private fun decodeScaledBitmap(file: File, maxPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while ((w / sample) > maxPx * 2 || (h / sample) > maxPx * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            if (raw.width > maxPx || raw.height > maxPx) {
                val scale = maxPx.toFloat() / maxOf(raw.width, raw.height)
                val tw = (raw.width * scale).toInt().coerceAtLeast(1)
                val th = (raw.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(raw, tw, th, true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else {
                raw
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeScaledBitmap failed: ${file.absolutePath}", e)
            null
        }
    }

    private fun createCircularBitmap(bitmap: Bitmap): Bitmap {
        val softwareBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val size = minOf(softwareBitmap.width, softwareBitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val rect = Rect(0, 0, size, size)
        val radius = size / 2f
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(softwareBitmap, null, rect, paint)
        return output
    }
}
