package net.vrkknn.andromuks.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.NotificationData
import net.vrkknn.andromuks.NotificationDataParser
import net.vrkknn.andromuks.TimelineEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The only entry point the rest of the app uses to update room widgets.
 *
 * Every method starts by asking [RoomWidgetStore] whether any widget is bound to the room, and
 * returns immediately if not. That guard is what makes it safe to call these from hot paths — the
 * notification post and `SyncIngestor.processRoom` — since with no widgets installed the cost is a
 * `Set.contains` on an empty set.
 *
 * See docs/WIDGET.md for the trigger matrix.
 */
object RoomWidgetUpdater {
    private const val TAG = "RoomWidgetUpdater"

    /**
     * How long to sit on sync-driven refreshes. Sync is chatty; without this a busy room would
     * queue a fetch per `sync_complete`. Optimistic in-place updates are *not* debounced — they
     * cost nothing and should feel immediate.
     */
    private const val SYNC_DEBOUNCE_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** roomId -> pending debounced refresh, so a burst collapses into one fetch. */
    private val pendingRefreshes = ConcurrentHashMap<String, Job>()

    /**
     * Ask Glance to repaint every widget from its stored snapshot. No data work.
     *
     * The revision bump is not optional bookkeeping — it is what makes the repaint show anything
     * new. `provideGlance` runs once per session, so the composition only observes values it read
     * through `currentState`; bumping [RoomWidget.REVISION_KEY] and then calling `updateAll` is the
     * pair that reloads the Glance state and re-reads [RoomWidgetStore]. `updateAll` on its own
     * recomposes with the previous data and looks like nothing happened.
     */
    fun redraw(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val manager = GlanceAppWidgetManager(appContext)
                manager.getGlanceIds(RoomWidget::class.java).forEach { glanceId ->
                    updateAppWidgetState(appContext, glanceId) { prefs ->
                        prefs[RoomWidget.REVISION_KEY] = (prefs[RoomWidget.REVISION_KEY] ?: 0) + 1
                    }
                }
                RoomWidget().updateAll(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Widget redraw failed: ${e.message}")
            }
        }
    }

    /**
     * Queue an authoritative refresh for [roomId], debounced by [SYNC_DEBOUNCE_MS].
     *
     * [reason] is logged only; it makes a logcat dump readable when several triggers are firing.
     */
    fun requestRefresh(context: Context, roomId: String, reason: String) {
        if (RoomWidgetStore.widgetIdsForRoom(context, roomId).isEmpty()) return
        val appContext = context.applicationContext
        pendingRefreshes.remove(roomId)?.cancel()
        pendingRefreshes[roomId] = scope.launch {
            delay(SYNC_DEBOUNCE_MS)
            pendingRefreshes.remove(roomId)
            if (BuildConfig.DEBUG) Log.d(TAG, "Refreshing $roomId ($reason)")
            RoomWidgetRefreshWorker.enqueue(appContext, roomId)
        }
    }

    /**
     * The refresh button. Paints a "refreshing" state straight away so the tap visibly does
     * something, then runs the fetch expedited and without the sync debounce.
     */
    fun requestManualRefresh(context: Context, roomId: String) {
        val appContext = context.applicationContext
        val widgetIds = RoomWidgetStore.widgetIdsForRoom(appContext, roomId)
        if (widgetIds.isEmpty()) return

        pendingRefreshes.remove(roomId)?.cancel()
        widgetIds.forEach { id ->
            val current = RoomWidgetStore.readSnapshot(appContext, id) ?: return@forEach
            RoomWidgetStore.writeSnapshot(appContext, id, current.copy(refreshing = true))
        }
        redraw(appContext)
        RoomWidgetRefreshWorker.enqueue(appContext, roomId, expedited = true)
    }

    /**
     * Mark [roomId]'s snapshot as no longer trustworthy and schedule a full refetch.
     *
     * Used for changes the widget cannot apply incrementally: a server-side `reset`, or an edit or
     * redaction of a message already on screen. Marking rather than clearing is deliberate — the
     * stale rows keep showing until real ones replace them, which beats blanking the widget for the
     * couple of seconds the refetch takes. The [RoomWidgetSnapshot.stale] flag also stops
     * [onSyncEvents] appending to a snapshot that is about to be thrown away.
     */
    fun invalidate(context: Context, roomId: String, reason: String) {
        val appContext = context.applicationContext
        val widgetIds = RoomWidgetStore.widgetIdsForRoom(appContext, roomId)
        if (widgetIds.isEmpty()) return
        widgetIds.forEach { id ->
            RoomWidgetStore.readSnapshot(appContext, id)?.let {
                RoomWidgetStore.writeSnapshot(appContext, id, it.copy(stale = true))
            }
        }
        requestRefresh(appContext, roomId, reason)
    }

    /** Refresh every configured widget — used on boot and when the host re-registers widgets. */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        RoomWidgetStore.boundRoomIds(appContext).forEach { roomId ->
            RoomWidgetRefreshWorker.enqueue(appContext, roomId)
        }
    }

    /**
     * A notification for [data]'s room was just posted.
     *
     * The push payload already carries everything a row needs — sender, display name, avatar, body,
     * timestamp — so the widget is updated **with no network at all**, immediately. The authoritative
     * refresh that follows only exists to reconcile formatting the payload can't express (an edit
     * applied server-side, a per-message profile) and to pick up anything the push omitted.
     */
    fun onRoomNotification(context: Context, data: NotificationData) {
        val appContext = context.applicationContext
        val widgetIds = RoomWidgetStore.widgetIdsForRoom(appContext, data.roomId)
        if (widgetIds.isEmpty()) return

        scope.launch {
            try {
                applyNotification(appContext, widgetIds, data)
            } catch (e: Exception) {
                Log.w(TAG, "Optimistic notification update failed for ${data.roomId}: ${e.message}")
            }
            RoomWidgetRefreshWorker.enqueue(appContext, data.roomId)
        }
    }

    private suspend fun applyNotification(context: Context, widgetIds: List<Int>, data: NotificationData) {
        val eventId = data.eventId?.takeIf { it.isNotBlank() } ?: return
        val creds = net.vrkknn.andromuks.utils.ExecApi.readCredentials(context)
        val senderName = data.senderDisplayName?.takeIf { it.isNotBlank() }
            ?: data.sender.removePrefix("@").substringBefore(":")
        val avatarPath = RoomWidgetAvatars.resolve(
            context = context,
            mxcUrl = data.avatarUrl,
            fallbackName = senderName,
            fallbackId = data.sender,
            homeserverUrl = creds.homeserverUrl,
            authToken = data.imageAuthToken?.takeIf { it.isNotBlank() } ?: creds.authToken,
        )
        val message = WidgetMessage(
            eventId = eventId,
            senderId = data.sender,
            senderName = senderName,
            senderAvatarPath = avatarPath,
            // Same one-line vocabulary the notification itself uses ("📷 Image"), so the widget row
            // and the notification agree about what just arrived.
            text = NotificationDataParser.createNotificationBody(data),
            timestamp = data.timestamp ?: System.currentTimeMillis(),
        )

        // count() rather than any(): every widget must be written, and the result only decides
        // whether a redraw is worth dispatching.
        val changed = widgetIds.count { id -> appendMessage(context, id, message) }
        if (changed > 0) redraw(context)
    }

    /** Append [message] to one widget's snapshot. False when there is nothing to write. */
    private fun appendMessage(context: Context, appWidgetId: Int, message: WidgetMessage): Boolean {
        val current = RoomWidgetStore.readSnapshot(context, appWidgetId) ?: return false
        // The authoritative refresh may already have landed this event.
        if (current.messages.any { it.eventId == message.eventId }) return false
        val limit = RoomWidgetStore.messageLimit(context, appWidgetId)
        RoomWidgetStore.writeSnapshot(
            context,
            appWidgetId,
            current.copy(
                messages = (current.messages + message).takeLast(limit),
                updatedAt = System.currentTimeMillis(),
                state = RoomWidgetSnapshot.State.OK,
            ),
        )
        return true
    }

    /**
     * New events arrived over live sync for [roomId].
     *
     * Called from `SyncIngestor.processRoom`, which parses events for widget-bound rooms even when
     * they are not in the timeline LRU — without that, a widget on a room the user hasn't opened
     * would never see anything.
     *
     * [requiresFullRefresh] carries `SyncIngestor`'s own `hasEditRedactionReaction || reset` flag.
     * Those mutate messages already on screen rather than adding new ones, and resolving them needs
     * the edit-chain machinery the widget deliberately lacks — so instead of guessing, the snapshot
     * is marked stale and refetched.
     */
    fun onSyncEvents(context: Context, roomId: String, events: List<TimelineEvent>, requiresFullRefresh: Boolean) {
        val appContext = context.applicationContext
        val widgetIds = RoomWidgetStore.widgetIdsForRoom(appContext, roomId)
        if (widgetIds.isEmpty() || events.isEmpty()) return

        if (requiresFullRefresh) {
            invalidate(appContext, roomId, reason = "sync-edit-or-redaction")
            return
        }

        scope.launch {
            val changed = widgetIds.count { id -> appendSyncEvents(appContext, id, roomId, events) }
            if (changed > 0) redraw(appContext)
        }
    }

    /** Fold [events] into one widget's snapshot. False when there is nothing to write. */
    private suspend fun appendSyncEvents(context: Context, appWidgetId: Int, roomId: String, events: List<TimelineEvent>): Boolean {
        val current = RoomWidgetStore.readSnapshot(context, appWidgetId) ?: return false
        // A stale snapshot is waiting on a full refetch; appending to it would paint rows that the
        // pending refresh is about to contradict.
        if (current.stale) return false
        val limit = RoomWidgetStore.messageLimit(context, appWidgetId)
        val updated = try {
            RoomWidgetRefresher.appendEvents(context, roomId, current, events, limit)
        } catch (e: Exception) {
            Log.w(TAG, "Sync append failed for $roomId: ${e.message}")
            null
        } ?: return false
        RoomWidgetStore.writeSnapshot(context, appWidgetId, updated)
        return true
    }
}
