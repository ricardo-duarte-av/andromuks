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
import androidx.core.content.pm.ShortcutManagerCompat
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.ExecApi
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.MediaUtils
import net.vrkknn.andromuks.utils.getUserAgent
import net.vrkknn.andromuks.utils.htmlToNotificationText

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
        private const val KEY_ROOM_NAME = "room_name"
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
        private const val KEY_FETCH_EVENT = "fetch_event"
        private const val KEY_MESSAGE_RECEIVED_AT = "message_received_at"

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
            roomName: String?,
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
            authToken: String,
            // When true (and an eventId is present), the worker fetches the full event over
            // /_gomuks/exec/get_event to enrich the notification — currently used to detect and
            // attach voice messages (m.audio + msc3245.voice). See doWork's audio block.
            fetchEvent: Boolean = false,
            // Wall-clock instant the triggering FCM was received. The worker re-checks the dismiss
            // tombstone against this immediately before re-posting, so a dismiss that landed during
            // the download window cannot be resurrected (Race 2). See NotificationDismissTracker.
            messageReceivedAt: Long = System.currentTimeMillis()
        ) {
            // Nothing deferred and no event to enrich → nothing to do.
            if (imageUrl == null && roomAvatarMxc == null && senderAvatarMxc == null && meAvatarMxc == null &&
                !(fetchEvent && eventId != null)
            ) return

            val data = Data.Builder()
                .putString(KEY_ROOM_ID, roomId)
                .putString(KEY_ROOM_NAME, roomName)
                .putString(KEY_EVENT_ID, eventId)
                .putBoolean(KEY_FETCH_EVENT, fetchEvent)
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
                .putLong(KEY_MESSAGE_RECEIVED_AT, messageReceivedAt)
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
                "Enqueued media update for room $roomId (image=${imageUrl != null}, roomAvatar=${roomAvatarMxc != null}, senderAvatar=${senderAvatarMxc != null}, meAvatar=${meAvatarMxc != null}, fetchEvent=$fetchEvent)"
            )
        }
    }

    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val roomName = inputData.getString(KEY_ROOM_NAME)
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
        val fetchEvent = inputData.getBoolean(KEY_FETCH_EVENT, false)
        val messageReceivedAt = inputData.getLong(KEY_MESSAGE_RECEIVED_AT, 0L)

        // Re-read auth tokens at run time so a delayed job uses fresh credentials. There are TWO
        // distinct tokens and conflating them breaks media downloads:
        //  - the SESSION token (`gomuks_auth` cookie) authenticates every /_gomuks/media/* request.
        //    This is what `IntelligentMediaCache.downloadAndCache` puts in the Cookie header. The
        //    proven avatar path (EnhancedNotificationDisplay.loadAvatarBitmap, ConversationsApi,
        //    PersonsApi) all pass the session token here.
        //  - the IMAGE-AUTH token is a media query-param token (`?image_auth=`), per push batch;
        //    it is NOT a valid session cookie. Using it as the cookie made cache-miss avatar/audio
        //    downloads fail (invalid cookie), which is why they only worked once already cached.
        val sharedPrefs = applicationContext.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        val homeserverUrl = sharedPrefs.getString("homeserver_url", "") ?: ""
        // Cookie auth: session token, falling back to the one captured at enqueue time.
        val authToken = net.vrkknn.andromuks.utils.CredentialStore.getAuthToken(sharedPrefs)
            .ifBlank { inputData.getString(KEY_AUTH_TOKEN) ?: "" }
        // ?image_auth= query token: per-batch token preferred, then the persisted image_auth token,
        // then the session token as a last resort.
        val batchToken = inputData.getString(KEY_IMAGE_AUTH_TOKEN) ?: ""

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

        val effectiveToken = batchToken
            .ifBlank { sharedPrefs.getString("image_auth_token", "") ?: "" }
            .ifBlank { authToken }
        val maxPx = (48f * applicationContext.resources.displayMetrics.density).toInt().coerceAtLeast(96)

        // 2. Event enrichment FIRST (if requested) — we need it before downloading so we can declare
        //    the notification kind and, for image/video messages, use the event's thumbnail (the
        //    poster frame for a video) over the full-size payload image. Fetched for every
        //    notification with an eventId now.
        val eventJson: JSONObject? = if (fetchEvent && eventId != null) {
            try {
                fetchEventJson(roomId, eventId)
            } catch (e: Exception) {
                Log.w(TAG, "get_event failed for room $roomId", e)
                null
            }
        } else null
        val notifKind = classifyKind(imageUrl, eventJson)
        Log.d(TAG, "Handling room $roomId notification: type=$notifKind (eventId=$eventId)")
        // Diagnostic: if we fetched an event but couldn't read a msgtype, dump what get_event gave
        // us so we can tell an undecrypted event apart from an unexpected shape.
        if (BuildConfig.DEBUG && eventJson != null &&
            decryptedContent(eventJson)?.optString("msgtype").isNullOrBlank()
        ) {
            Log.d(
                TAG,
                "Unresolved event $eventId: topKeys=${eventJson.keys().asSequence().toList()}, " +
                    "hasDecrypted=${eventJson.has("decrypted")}, decrypted_type=${eventJson.optString("decrypted_type")}, type=${eventJson.optString("type")}"
            )
        }

        // Image source: prefer the event's thumbnail — for an image it's smaller (better for the
        // wake/metered path and the downscaled display); for a video it's the poster frame, which is
        // what we want to show. Fall back to the full-size payload image (and this also recovers
        // image notifications whose payload URL was unresolvable — the thumbnail still works).
        val thumbImage = eventJson?.let { resolveImageThumbnail(it, homeserverUrl, effectiveToken) }
        val payloadImage = imageUrl?.let { ImageSource(mxcUrl, it, mimeType) }
        // Try the thumbnail first; if it fails, the payload image is the fallback (and vice-versa).
        val imageSources = listOfNotNull(thumbImage, payloadImage)
        val imageRequested = imageSources.isNotEmpty()

        // 3. Download everything in parallel. Avatars and the image are all cache-first; the batch
        //    token is preferred for media auth, the session token is the fallback.
        data class Downloads(
            val image: Pair<File, String>?,   // (file, mime) — mime tracks which source produced it
            val room: Bitmap?,
            val sender: Bitmap?,
            val me: Bitmap?
        )
        val dl = coroutineScope {
            val imageDeferred = async {
                var result: Pair<File, String>? = null
                for (src in imageSources) {
                    val f = downloadImageFile(src.mxc, src.httpUrl, authToken)
                    if (f != null) { result = f to src.mime; break }
                }
                result
            }
            val roomDeferred = async { roomAvatarMxc?.let { loadCircular(it, homeserverUrl, effectiveToken, authToken, maxPx) } }
            val senderDeferred = async { senderAvatarMxc?.let { loadCircular(it, homeserverUrl, effectiveToken, authToken, maxPx) } }
            val meDeferred = async { meAvatarMxc?.let { loadCircular(it, homeserverUrl, effectiveToken, authToken, maxPx) } }
            Downloads(imageDeferred.await(), roomDeferred.await(), senderDeferred.await(), meDeferred.await())
        }

        // Wrap a downloaded image in a content:// URI the notification subsystem can read.
        val imageUri = dl.image?.first?.let { contentUriForFile(it) }
        val imageMime = dl.image?.second ?: mimeType

        val senderIcon = dl.sender?.let { IconCompat.createWithAdaptiveBitmap(it) }
        val meIcon = dl.me?.let { IconCompat.createWithBitmap(it) }

        val audio: AudioOutcome? = eventJson?.let {
            try {
                audioOutcomeFrom(it, homeserverUrl, authToken)
            } catch (e: Exception) {
                Log.w(TAG, "Audio enrichment failed for room $roomId", e)
                null
            }
        }
        val audioUri = audio?.uri
        val audioCaption = audio?.caption

        // Non-audio media caption (m.image/m.video caption, or an m.file label): use it in place of
        // the generic Phase-1 text. Null when nothing should override (e.g. captionless image).
        val mediaCaption = eventJson?.let { captionForMedia(it) }

        // Formatted text for a plain m.text/m.notice message, rendered from sanitized_html (only when
        // it carries real formatting). Piggybacks on the get_event fetch above; media/audio captions
        // take precedence since those msgtypes never produce richText.
        val richText = eventJson?.let { richTextForTextMessage(it) }

        val anyApplied = imageUri != null || senderIcon != null || meIcon != null || dl.room != null ||
            audioUri != null || audioCaption != null || mediaCaption != null || richText != null
        if (!anyApplied) {
            // Nothing to apply. Only retry if something was actually *requested* and failed —
            // a plain text message (event fetched, not audio) is a legitimate no-op, not a failure.
            val somethingRequested = imageRequested || roomAvatarMxc != null ||
                senderAvatarMxc != null || meAvatarMxc != null
            val willRetry = somethingRequested && runAttemptCount < 3
            Androlog(
                "Notifications",
                "Room $roomId: Phase-2 produced nothing on attempt ${runAttemptCount + 1} " +
                    "(image=$imageRequested, avatars=${roomAvatarMxc != null || senderAvatarMxc != null || meAvatarMxc != null}, fetchEvent=$fetchEvent). " +
                    (if (willRetry) "Will retry." else "Nothing to do; notification keeps its Phase-1 content.")
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

        // Target message: the one this worker is enriching, matched by timestamp so a newer text
        // message that arrived between Phase 1 and now is not overwritten. Image and audio are
        // mutually exclusive for a single event, so they share one target and one media slot.
        val targetMediaUri = imageUri ?: audioUri
        val targetMediaMime = if (imageUri != null) imageMime else (audio?.mime ?: "")
        val targetCaption = audioCaption ?: mediaCaption ?: richText   // caption / formatted text replaces the Phase-1 text
        val hasTargetChange = targetMediaUri != null || targetCaption != null
        val targetIndex = if (hasTargetChange) {
            if (messageTimestamp > 0L) {
                messages.indexOfLast { it.timestamp == messageTimestamp }.takeIf { it >= 0 } ?: messages.lastIndex
            } else {
                messages.lastIndex
            }
        } else {
            -1
        }

        // 5. Rebuild: patch "me" Person icon (root), each message's sender Person icon, and the
        //    target message's text/media data — all in one pass.
        val newUser = if (meIcon != null) rebuildPerson(existingStyle.user, meIcon) else existingStyle.user
        val newStyle = MessagingStyle(newUser)
            .setConversationTitle(existingStyle.conversationTitle)
            .setGroupConversation(existingStyle.isGroupConversation)
        messages.forEachIndexed { idx, msg ->
            val person = msg.person
            val updatePerson = senderIcon != null && person != null && person.key == senderId
            val applyTarget = hasTargetChange && idx == targetIndex
            if (updatePerson || applyTarget) {
                val newPerson = if (updatePerson) rebuildPerson(person, senderIcon) else person
                val newText = if (applyTarget && targetCaption != null) targetCaption else msg.text
                val rebuilt = MessagingStyle.Message(newText, msg.timestamp, newPerson)
                if (applyTarget && targetMediaUri != null) {
                    rebuilt.setData(targetMediaMime, targetMediaUri)
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
            // Suppress the re-alert (no second sound/vibrate/heads-up) WITHOUT demoting the
            // notification to the "silent" rank. setSilent(true) here moved the notification into
            // Android's silent category, which drops its status-bar icon and clears the message from
            // the People/Conversation widget the instant the worker re-posted (<1s after Phase 1).
            // setOnlyAlertOnce keeps the alerting rank — status-bar icon and widget entry survive —
            // while still not re-buzzing. This matches what Phase 1 itself does on an update.
            .setOnlyAlertOnce(true)
            // Keep this child in the shared group; GROUP_ALERT_CHILDREN matches the Phase 1 post.
            .setGroup(EnhancedNotificationDisplay.NOTIFICATION_GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setWhen(existing.notification.`when`)

        if (newLargeIcon != null) {
            builder.setLargeIcon(newLargeIcon)
        } else {
            restoreLargeIcon(existing.notification)?.let { builder.setLargeIcon(it) }
        }

        // Preserve conversation linkage and bubble so a media update never drops them.
        NotificationCompat.getLocusId(existing.notification)?.let { builder.setLocusId(it) }
        NotificationCompat.getBubbleMetadata(existing.notification)?.let { builder.setBubbleMetadata(it) }

        // Preserve event_id extra and reply / mark-read actions. Use NotificationCompat.getAction
        // (not raw Notification.Action copies) so each action keeps its icon and — critically — its
        // RemoteInput: the inline-reply text field would otherwise be dropped on the silent re-post.
        val storedEventId = existing.notification.extras?.getString("event_id") ?: eventId
        storedEventId?.let { builder.addExtras(android.os.Bundle().apply { putString("event_id", it) }) }
        var replyActionForWear: NotificationCompat.Action? = null
        val actionCount = NotificationCompat.getActionCount(existing.notification)
        for (i in 0 until actionCount) {
            val action = NotificationCompat.getAction(existing.notification, i) ?: continue
            builder.addAction(action)
            // The reply action is the one carrying a RemoteInput; surface it on the watch too,
            // matching the WearableExtender attached by Phase 1 (showEnhancedNotification).
            if (replyActionForWear == null && action.remoteInputs?.isNotEmpty() == true) {
                replyActionForWear = action
            }
        }
        replyActionForWear?.let {
            builder.extend(NotificationCompat.WearableExtender().addAction(it))
        }

        // Re-publish the conversation shortcut BEFORE notifying so its icon picks up the freshly
        // downloaded room avatar. This is the avatar Android actually renders for a conversation
        // notification (MessagingStyle + shortcut + LocusId) — the large icon patched above is NOT
        // the one shown, and the notification resolves the shortcut icon at post time. The Phase-1
        // shortcut was built on the FCM thread with a cache-cold avatar (lettermark fallback); now
        // that loadCircular() has warmed IntelligentMediaCache for the same mxc key,
        // createShortcutInfoCompat() rebuilds the real icon (updateSingleShortcut force-refreshes it).
        if (dl.room != null && roomAvatarMxc != null) {
            try {
                val conversationsApi = ConversationsApi(applicationContext, homeserverUrl, authToken, "")
                conversationsApi.updateShortcutForNotificationSync(
                    RoomItem(
                        id = roomId,
                        name = roomName ?: roomId,
                        messagePreview = null,
                        messageSender = null,
                        unreadCount = 1,
                        highlightCount = 0,
                        avatarUrl = roomAvatarMxc,
                        sortingTimestamp = existing.notification.`when`.takeIf { it > 0L } ?: messageTimestamp,
                        canonicalAlias = null,
                        latestEventId = eventId
                    )
                )
                Androlog("Notifications", "Room $roomId: conversation shortcut icon refreshed with downloaded room avatar.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh conversation shortcut icon for room $roomId", e)
            }
        }

        // Conversation binding: prefer the FULL ShortcutInfoCompat (what Phase 1 uses via
        // setShortcutInfo) over a bare shortcut id, so the People/Conversation widget keeps its
        // conversation linkage on the re-post. Resolved AFTER the re-publish above so it reflects
        // the freshly-refreshed shortcut icon. Falls back to the id alone if it can't be resolved.
        val fullShortcut = try {
            ShortcutManagerCompat.getShortcuts(
                applicationContext,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                    ShortcutManagerCompat.FLAG_MATCH_PINNED or
                    ShortcutManagerCompat.FLAG_MATCH_MANIFEST
            ).firstOrNull { it.id == roomId }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve conversation shortcut for room $roomId", e)
            null
        }
        if (fullShortcut != null) {
            builder.setShortcutInfo(fullShortcut)
        } else {
            builder.setShortcutId(roomId)
        }

        // Dismiss/re-post race guard (Race 2): the start-of-run check at the top of doWork() is not
        // atomic with this re-post — a dismiss may have cancelled the notification during the
        // multi-second download window above. Re-check under the same per-room monitor the dismiss
        // path takes, immediately before notify(): bail if the notification is gone, OR if this
        // room was dismissed after our triggering message was received (the latter also stops a
        // stale worker from clobbering a newer notification that re-posted in the meantime).
        val reposted = synchronized(NotificationDismissTracker.lockFor(roomId)) {
            val stillActive = systemNm.activeNotifications.any { it.id == notifId }
            if (!stillActive || NotificationDismissTracker.isDismissedAfter(roomId, messageReceivedAt)) {
                false
            } else {
                NotificationManagerCompat.from(applicationContext).notify(notifId, builder.build())
                true
            }
        }
        if (!reposted) {
            Androlog(
                "Notifications",
                "Room $roomId: worker re-post skipped — dismissed during download window (msgReceivedAt=$messageReceivedAt)"
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "Worker re-post skipped for $roomId — dismissed during download window")
            return Result.success()
        }
        EnhancedNotificationDisplay.refreshGroupSummary(applicationContext, justPostedChild = true)

        // Phase-2 ConversationStatus upgrade: now that get_event has told us the real msgtype,
        // refine the Phase-1 ACTIVITY_OTHER into video/audio/location, and add a DM availability
        // hint. Same status id, so it overwrites the Phase-1 status in place. Only after a
        // successful re-post (room was not read during the download window). The settle + double
        // push (see pushConversationStatusSettled) advances the People Space service's read-then-
        // update notification cache so the tile reflects THIS message instead of lagging one behind.
        // doWork is suspend and WorkManager keeps the process alive for the waits.
        ConversationsApi.pushConversationStatusSettled(
            applicationContext,
            roomId,
            messageTimestamp.takeIf { it > 0L } ?: messageReceivedAt,
            msgtype = eventJson?.let { decryptedContent(it)?.optString("msgtype") },
            isDirectMessage = !isGroupRoom
        )

        // 8. Keep the in-memory MessagingStyle cache consistent so a later rebuild (new message in
        //    the room) keeps the media/avatars instead of reverting to the Phase-1 placeholder.
        //    Covers both the image and a downloaded voice clip (same data slot).
        if (targetMediaUri != null && messageTimestamp > 0L) {
            EnhancedNotificationDisplay.upgradeMessageToImage(roomId, messageTimestamp, targetMediaMime, targetMediaUri)
        }
        if (senderIcon != null) {
            EnhancedNotificationDisplay.upgradeAvatarsInCache(roomId, senderId, senderIcon)
        }

        if (BuildConfig.DEBUG) Log.d(
            TAG,
            "Notification updated for room $roomId (image=${imageUri != null}, audio=${audioUri != null}, caption=${audioCaption != null}, richText=${richText != null}, room=${dl.room != null}, sender=${dl.sender != null}, me=${dl.me != null})"
        )
        Androlog(
            "Notifications",
            "Room $roomId: notification updated (image=${imageUri != null}, audio=${audioUri != null}, richText=${richText != null}, room=${dl.room != null}, sender=${dl.sender != null}, me=${dl.me != null})."
        )

        // Retry transient media failures so they land on a later attempt (other parts may have
        // already posted): a requested image we couldn't fetch, or a voice clip whose download
        // failed (its caption already posted, but the playable clip is still worth retrying).
        val voiceDownloadPending = audio?.retryDownload == true && audioUri == null
        val imagePending = imageRequested && imageUri == null
        return if ((imagePending || voiceDownloadPending) && runAttemptCount < 3) Result.retry() else Result.success()
    }

    /**
     * Result of enriching a notification from a fetched m.audio event.
     *  - [uri]: a content:// URI for a downloaded **voice** clip (null for generic audio, or on
     *    download failure). Set via [setData] so Android Auto / Wear can play it.
     *  - [mime]: the audio MIME for [setData] (e.g. "audio/ogg").
     *  - [caption]: replacement message text (🎤 voice / 🎵 file label).
     *  - [retryDownload]: true when a voice clip was wanted but the download failed, so the worker
     *    should retry (the caption already posted regardless).
     */
    private data class AudioOutcome(
        val uri: android.net.Uri?,
        val mime: String,
        val caption: String?,
        val retryDownload: Boolean = false,
    )

    /** A candidate image to download for the notification (thumbnail or full-size payload image). */
    private data class ImageSource(val mxc: String?, val httpUrl: String, val mime: String)

    /**
     * If [eventJson] is an m.image / m.video / m.file message **with a thumbnail**, return it as an
     * [ImageSource]. For an image this is a smaller copy (better for the FCM wake/metered path and
     * the notification's downscaled display); for a video it's the poster frame; for a file it's an
     * optional preview (e.g. a PDF first page) — most files have none, so this is null then. All
     * carry the thumbnail the same way: encrypted → `info.thumbnail_file.url` (served decrypted via
     * `?encrypted=true`); plaintext → `info.thumbnail_url`. Returns null when there is no thumbnail.
     */
    private fun resolveImageThumbnail(
        eventJson: JSONObject,
        homeserverUrl: String,
        effectiveToken: String,
    ): ImageSource? {
        val content = decryptedContent(eventJson) ?: return null
        val msgtype = content.optString("msgtype")
        if (msgtype != "m.image" && msgtype != "m.video" && msgtype != "m.file") return null
        val info = content.optJSONObject("info") ?: return null

        val thumbFile = info.optJSONObject("thumbnail_file")
        val isEncrypted = thumbFile != null
        val mxc = (thumbFile?.optString("url")?.takeIf { it.isNotBlank() }
            ?: info.optString("thumbnail_url").takeIf { it.isNotBlank() }) ?: return null
        val base = MediaUtils.mxcToHttpUrl(mxc, homeserverUrl, registerMapping = false) ?: return null

        var url = base + (if (base.contains("?")) "&" else "?") + "encrypted=$isEncrypted"
        if (effectiveToken.isNotBlank()) url += "&image_auth=$effectiveToken"
        val mime = info.optJSONObject("thumbnail_info")?.optString("mimetype")
            ?.substringBefore(";")?.trim()?.takeIf { it.isNotBlank() } ?: "image/jpeg"
        return ImageSource(mxc, url, mime)
    }

    // Dedicated client for audio: IntelligentMediaCache.downloadAndCache() validates that the body
    // is an image (captive-portal guard) and would reject ogg/mp3, so audio gets its own path.
    private val audioHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Fetch the full event over the HTTP `/_gomuks/exec/get_event` endpoint, or null. */
    private suspend fun fetchEventJson(roomId: String, eventId: String): JSONObject? {
        val creds = ExecApi.readCredentials(applicationContext)
        if (!creds.isValid()) {
            Log.w(TAG, "get_event: missing credentials for room $roomId")
            return null
        }
        val body = JSONObject().apply {
            put("room_id", roomId)
            put("event_id", eventId)
        }
        return withContext(Dispatchers.IO) {
            when (val r = ExecApi.execRaw(creds, "get_event", body)) {
                is ExecApi.ExecResult.Success -> r.data as? JSONObject
                else -> {
                    Log.w(TAG, "get_event failed for $eventId: $r")
                    null
                }
            }
        }
    }

    /**
     * The usable message content from a `get_event` `database.Event`. `get_event` returns the raw
     * DB row: an **encrypted** event keeps `type: "m.room.encrypted"` with the ciphertext in
     * `content`, and the decrypted body in a separate `decrypted` field (`decrypted_type` holds its
     * real type). A plaintext event has its content directly in `content`. So: prefer `decrypted`,
     * fall back to `content`. (This differs from the sync/frontend view, which pre-merges them.)
     */
    private fun decryptedContent(eventJson: JSONObject): JSONObject? =
        eventJson.optJSONObject("decrypted") ?: eventJson.optJSONObject("content")

    /** Voice-note marker is a *present key* (often an empty object) — test presence, not value. */
    private fun isVoiceMessage(content: JSONObject): Boolean =
        content.has("org.matrix.msc3245.voice") ||
        content.has("org.matrix.msc3245.voice.v2") ||
        content.has("m.voice")

    /**
     * Human-readable kind of message the notification represents, for logging. An image is known
     * from the FCM payload (no fetch); everything else is derived from the fetched event's
     * `content.msgtype` (gomuks normalises encrypted events, inlining the decrypted body), with the
     * audio/voice split applied. Falls back to the raw event `type` for non-`m.room.message` events.
     */
    private fun classifyKind(imageUrl: String?, eventJson: JSONObject?): String {
        if (imageUrl != null) return "image"
        if (eventJson == null) return "text/unknown (no event fetch)"
        val content = decryptedContent(eventJson)
        return when (val msgtype = content?.optString("msgtype").orEmpty()) {
            "m.text" -> "text"
            "m.notice" -> "notice"
            "m.emote" -> "emote"
            "m.image" -> "image"
            "m.video" -> "video"
            "m.file" -> "file"
            "m.location" -> "location"
            "m.audio" -> if (content != null && isVoiceMessage(content)) "voice" else "audio"
            // No msgtype: a non-message event, or an encrypted event we couldn't read decrypted.
            "" -> eventJson.optString("decrypted_type").ifBlank { eventJson.optString("type") }.ifBlank { "unknown" }
            else -> "other:$msgtype"
        }
    }

    /**
     * Extract a media message's caption per the Matrix spec (MSC2530): when `filename` is present
     * and the `body` differs from it, `body` (or `formatted_body`) is the **caption**. When
     * `filename` is absent, `body` *is* the filename — there is no caption. Returns null when there
     * is no caption. Prefers `formatted_body` (HTML → plain) so styled captions read correctly.
     */
    private fun extractCaption(content: JSONObject): String? {
        val filename = content.optString("filename").takeIf { it.isNotBlank() } ?: return null
        val body = content.optString("body").takeIf { it.isNotBlank() } ?: return null
        if (body == filename) return null
        val formatted = content.optString("formatted_body").takeIf { it.isNotBlank() }
        return if (formatted != null) {
            try { htmlToNotificationText(formatted).toString() } catch (e: Exception) { body }
        } else {
            body
        }
    }

    /**
     * Caption line for a non-audio media message (m.image / m.video / m.file), glyph-prefixed by
     * type, or null when nothing should override the Phase-1 text.
     *  - **image/video**: only when there's an actual caption — the thumbnail already conveys the
     *    content, so a captionless image keeps the Phase-1 text.
     *  - **file**: *always* a label — there's no visual, so the filename (+ human-readable size) is
     *    far more useful than the generic "Sent a file"; a real caption wins over the filename.
     */
    private fun captionForMedia(eventJson: JSONObject): String? {
        val content = decryptedContent(eventJson) ?: return null
        val caption = extractCaption(content)
        return when (content.optString("msgtype")) {
            "m.image" -> caption?.let { "📷 $it" }
            "m.video" -> caption?.let { "🎬 $it" }
            "m.file" -> {
                val label = caption
                    ?: content.optString("filename").takeIf { it.isNotBlank() }
                    ?: content.optString("body").takeIf { it.isNotBlank() }
                    ?: "File"
                val sizeSuffix = formatSize(content.optJSONObject("info")?.optLong("size", 0L) ?: 0L)
                "📄 $label" + (sizeSuffix?.let { " ($it)" } ?: "")
            }
            else -> null
        }
    }

    /**
     * For a plain text message (m.text / m.notice), render the event's
     * `local_content.sanitized_html` into a formatted notification CharSequence — real
     * bold/italic/underline/strikethrough/code/link/quote/list spans, via [htmlToNotificationText]
     * — replacing the markdown-guessing that Phase 1 applies to the plain `body`. This piggybacks
     * on the `get_event` fetch the worker already performs (no extra network).
     *
     * Returns null when the event isn't a plain text message, has no sanitized_html, or renders to
     * *unstyled* text. The unstyled case matters: plain prose renders identically to the Phase-1
     * body, so overriding it would force a silent re-post for no visible change. Requiring at least
     * one span means we only upgrade messages that actually carry formatting (which includes
     * MSC2191 maths — rendered as a monospace `<code>` span — so the markdown parser can no longer
     * mangle e.g. `$a*b*c$` into italics).
     */
    private fun richTextForTextMessage(eventJson: JSONObject): CharSequence? {
        val content = decryptedContent(eventJson) ?: return null
        val msgtype = content.optString("msgtype")
        if (msgtype != "m.text" && msgtype != "m.notice") return null
        val sanitized = eventJson.optJSONObject("local_content")
            ?.optString("sanitized_html")?.takeIf { it.isNotBlank() } ?: return null
        val rendered = try {
            htmlToNotificationText(sanitized)
        } catch (e: Exception) {
            Log.w(TAG, "richTextForTextMessage: failed to render sanitized_html", e)
            return null
        }
        if (rendered.isEmpty()) return null
        val hasSpans = rendered.getSpans(0, rendered.length, Any::class.java).isNotEmpty()
        return if (hasSpans) rendered else null
    }

    /** Human-readable byte size (B / KB / MB / GB), or null when unknown. */
    private fun formatSize(bytes: Long): String? {
        if (bytes <= 0L) return null
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${bytes} B" else "%.1f %s".format(value, units[unit])
    }

    /**
     * If [eventJson] is an m.audio message, produce an [AudioOutcome]. Voice messages
     * (msc3245.voice) are small and get downloaded + attached for Auto/Wear playback; generic audio
     * (music/podcast) gets a metadata caption only — no heavy background download. Returns null for
     * non-audio events (nothing to enrich here yet).
     */
    private suspend fun audioOutcomeFrom(
        eventJson: JSONObject,
        homeserverUrl: String,
        authToken: String,
    ): AudioOutcome? {
        // Read the decrypted body for encrypted events (raw `content` is ciphertext there).
        val content = decryptedContent(eventJson) ?: return null
        if (content.optString("msgtype") != "m.audio") return null

        val info = content.optJSONObject("info")
        val durationLabel = formatDuration(info?.optLong("duration", 0L) ?: 0L)
        val durationSuffix = durationLabel?.let { " ($it)" } ?: ""
        // A real caption (body ≠ filename) wins over the type label / filename.
        val caption = extractCaption(content)

        if (!isVoiceMessage(content)) {
            // Generic audio (music/podcast): caption if any, else the filename — plus duration.
            val name = caption
                ?: content.optString("filename").takeIf { it.isNotBlank() }
                ?: content.optString("body").takeIf { it.isNotBlank() }
                ?: "Audio file"
            return AudioOutcome(uri = null, mime = "", caption = "🎵 $name$durationSuffix")
        }

        val voiceCaption = "🎤 " + (caption ?: "Voice message") + durationSuffix

        // Encrypted media → content.file.url (gomuks decrypts server-side when ?encrypted=true);
        // plaintext → content.url. The /_gomuks/media endpoint REQUIRES the encrypted param.
        val fileObj = content.optJSONObject("file")
        val isEncrypted = fileObj != null
        val mxc = (fileObj?.optString("url")?.takeIf { it.isNotBlank() }
            ?: content.optString("url").takeIf { it.isNotBlank() })
            ?: return AudioOutcome(uri = null, mime = "", caption = voiceCaption)
        val mime = info?.optString("mimetype")?.substringBefore(";")?.trim()?.takeIf { it.isNotBlank() }
            ?: "audio/ogg"
        val base = MediaUtils.mxcToHttpUrl(mxc, homeserverUrl, registerMapping = false)
            ?: return AudioOutcome(uri = null, mime = mime, caption = voiceCaption, retryDownload = false)
        val url = base + (if (base.contains("?")) "&" else "?") + "encrypted=$isEncrypted"

        val file = downloadAudioFile(mxc, url, authToken, extForMime(mime))
        val uri = file?.let { contentUriForFile(it) }
        return AudioOutcome(
            uri = uri,
            mime = mime,
            caption = voiceCaption,
            retryDownload = uri == null, // wanted the clip but didn't get it → retry later
        )
    }

    /**
     * Download a (typically small) audio clip to the FileProvider-exposed cache dir and return the
     * file, or null. No image sniffing — unlike [IntelligentMediaCache]. Cache-first by mxc.
     */
    private suspend fun downloadAudioFile(
        mxc: String,
        httpUrl: String,
        authToken: String,
        ext: String,
    ): File? = withContext(Dispatchers.IO) {
        try {
            // Must live under intelligent_media_cache/ — the only writable dir file_paths.xml exposes.
            val dir = File(applicationContext.cacheDir, "intelligent_media_cache")
            if (!dir.exists()) dir.mkdirs()
            val safe = mxc.removePrefix("mxc://").replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = File(dir, "notif_audio_$safe.$ext")
            if (out.exists() && out.length() > 0L) return@withContext out

            val request = Request.Builder()
                .url(httpUrl)
                .header("Cookie", "gomuks_auth=$authToken")
                .header("User-Agent", getUserAgent())
                .build()
            audioHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Audio download HTTP ${resp.code} for $mxc")
                    return@withContext null
                }
                val respBody = resp.body
                val tmp = File(dir, "${out.name}.tmp")
                tmp.outputStream().use { o -> respBody.byteStream().use { it.copyTo(o) } }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    return@withContext null
                }
                out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio download failed for $mxc", e)
            null
        }
    }

    /** Wrap a file in a content:// URI and grant read to systemui, Android Auto, and ourselves. */
    private fun contentUriForFile(file: File): android.net.Uri? = try {
        val uri = FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.fileprovider", file)
        listOf("com.android.systemui", "com.google.android.projection.gearhead", applicationContext.packageName)
            .forEach { pkg ->
                try {
                    applicationContext.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
            }
        uri
    } catch (e: Exception) {
        Log.e(TAG, "FileProvider URI creation failed", e)
        null
    }

    /** "m:ss" for a positive duration in ms, or null when unknown. */
    private fun formatDuration(ms: Long): String? {
        if (ms <= 0L) return null
        val totalSec = (ms / 1000).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** File extension for a bare audio MIME (so players key off a sane name). */
    private fun extForMime(mime: String): String = when {
        mime.contains("ogg") || mime.contains("opus") -> "ogg"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
        mime.contains("wav") -> "wav"
        mime.contains("webm") -> "webm"
        else -> "ogg"
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
