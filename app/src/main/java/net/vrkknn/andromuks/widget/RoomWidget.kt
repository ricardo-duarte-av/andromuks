package net.vrkknn.andromuks.widget

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import net.vrkknn.andromuks.MainActivity
import net.vrkknn.andromuks.R
import java.io.File

/**
 * The home-screen room widget: room header, then the last few messages with sender avatars.
 *
 * Rendering is **pure** — it reads a [RoomWidgetSnapshot] from [RoomWidgetStore] and draws it. There
 * is no network, no cache lookup and no event interpretation here; all of that happened in
 * [RoomWidgetRefresher] before the snapshot was written. See docs/WIDGET.md.
 */
class RoomWidget : GlanceAppWidget() {
    /** Responsive: we re-render on resize so the message count can follow the available height. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Glance identifies widgets by GlanceId; everything we persist is keyed by the platform
        // appWidgetId, which is also what AppWidgetManager and our receiver speak.
        val appWidgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve an appWidgetId from $id: ${e.message}")
            null
        }

        provideContent {
            // CRITICAL: the store must be read INSIDE provideContent, keyed on a value Glance
            // actually tracks.
            //
            // provideGlance runs once per session and provideContent never returns, so anything
            // read above this line is captured exactly once — at session start. A widget's session
            // starts the moment it is bound to the host, which is BEFORE the configuration activity
            // has picked a room, so reading the store up there captured "unconfigured" and every
            // later updateAll() recomposed with that same stale capture. The widget sat on
            // "Tap to configure" forever while refreshes ran and wrote snapshots nobody read.
            //
            // Only currentState reads are reactive: updateAll() reloads the Glance state DataStore
            // and recomposes. RoomWidgetUpdater bumps REVISION_KEY on every write, so this remember
            // re-reads the store exactly when there is something new to read.
            val revision = currentState(REVISION_KEY) ?: 0
            val data = remember(revision, appWidgetId) { readWidgetData(context, appWidgetId) }

            GlanceTheme {
                WidgetBody(snapshot = data.snapshot, roomId = data.roomId)
            }
        }
    }

    /** Everything one render needs from [RoomWidgetStore], read in one go. */
    private data class WidgetData(val snapshot: RoomWidgetSnapshot?, val roomId: String?)

    private fun readWidgetData(context: Context, appWidgetId: Int?): WidgetData {
        if (appWidgetId == null) return WidgetData(null, null)
        val roomId = RoomWidgetStore.roomIdFor(context, appWidgetId)
        val stored = RoomWidgetStore.readSnapshot(context, appWidgetId)
        val configuredName = RoomWidgetStore.configuredRoomName(context, appWidgetId)
        return WidgetData(
            // A configured widget whose snapshot hasn't landed yet shows its room name and a
            // spinner, never the unconfigured placeholder.
            snapshot = stored ?: roomId?.let { RoomWidgetSnapshot.loading(it, configuredName ?: it) },
            roomId = roomId,
        )
    }

    @Composable
    private fun WidgetBody(snapshot: RoomWidgetSnapshot?, roomId: String?) {
        val context = LocalContext.current
        val visibleCount = fittingMessageCount()

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(openRoomAction(context, roomId ?: snapshot?.roomId)),
        ) {
            WidgetHeader(snapshot, roomId ?: snapshot?.roomId)
            Spacer(GlanceModifier.size(8.dp))
            when {
                snapshot == null -> CenteredNotice("Tap to configure")

                snapshot.state == RoomWidgetSnapshot.State.SIGNED_OUT -> CenteredNotice("Sign in to Andromuks")

                snapshot.state == RoomWidgetSnapshot.State.LOADING && snapshot.messages.isEmpty() ->
                    CenteredNotice("Loading…")

                snapshot.messages.isEmpty() && snapshot.state == RoomWidgetSnapshot.State.ERROR ->
                    CenteredNotice("Couldn't load messages")

                snapshot.messages.isEmpty() -> CenteredNotice("No messages yet")

                else -> MessageList(snapshot, visibleCount)
            }
        }
    }

    @Composable
    private fun WidgetHeader(snapshot: RoomWidgetSnapshot?, roomId: String?) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarImage(path = snapshot?.roomAvatarPath, sizeDp = 24)
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = snapshot?.roomName?.takeIf { it.isNotBlank() } ?: "Andromuks",
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Image(
                provider = ImageProvider(R.drawable.ic_widget_refresh),
                contentDescription = "Refresh",
                colorFilter = androidx.glance.ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier
                    .size(24.dp)
                    .clickable(
                        actionRunCallback<RefreshWidgetAction>(
                            // Prefer the binding over the snapshot: a widget whose first refresh
                            // hasn't landed has no snapshot yet, and its refresh button must still
                            // work — that is exactly the state you want to retry from.
                            actionParametersOf(RefreshWidgetAction.roomIdKey to (roomId ?: snapshot?.roomId ?: "")),
                        ),
                    ),
            )
        }
    }

    /**
     * The newest [visibleCount] messages, oldest first.
     *
     * A plain [Column], not a `LazyColumn`: a scrollable list inside a widget is the wrong
     * interaction — it puts a scrollbar on the home screen and competes with the launcher's own
     * gestures. Instead the widget shows exactly what fits at its current size (see
     * [fittingMessageCount]) and nothing more, so there is never anything to scroll to.
     */
    @Composable
    private fun MessageList(snapshot: RoomWidgetSnapshot, visibleCount: Int) {
        val context = LocalContext.current
        val messages = snapshot.messages.takeLast(visibleCount)
        Column(modifier = GlanceModifier.fillMaxSize()) {
            messages.forEachIndexed { index, message ->
                // Sender identity is drawn only when the sender changes, matching the timeline's
                // grouping — and halving the number of bitmaps in the RemoteViews transaction.
                val isNewSender = index == 0 || messages[index - 1].senderId != message.senderId
                MessageRow(
                    message = message,
                    showSender = isNewSender,
                    onClick = openRoomAction(context, snapshot.roomId, message.eventId),
                )
            }
        }
    }

    @Composable
    private fun MessageRow(message: WidgetMessage, showSender: Boolean, onClick: androidx.glance.action.Action) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clickable(onClick),
        ) {
            if (showSender) {
                AvatarImage(path = message.senderAvatarPath, sizeDp = 28)
            } else {
                Spacer(GlanceModifier.size(28.dp))
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                if (showSender) {
                    Text(
                        text = message.senderName,
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                Text(
                    text = message.text,
                    maxLines = 2,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                )
            }
        }
    }

    /**
     * Draw a pre-rendered circular avatar from disk, or nothing if the file is gone.
     *
     * Decoding happens here rather than at refresh time because a `Bitmap` cannot be persisted in
     * the snapshot — but the file was already scaled to ~96 px by [RoomWidgetAvatars], so this is a
     * cheap decode, not a resize.
     */
    @Composable
    private fun AvatarImage(path: String?, sizeDp: Int) {
        val bitmap = path?.let { p ->
            try {
                File(p).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap != null) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = null,
                modifier = GlanceModifier.size(sizeDp.dp).cornerRadius((sizeDp / 2).dp),
            )
        } else {
            Spacer(GlanceModifier.size(sizeDp.dp))
        }
    }

    @Composable
    private fun CenteredNotice(text: String) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        }
    }

    /**
     * How many message rows fit at the widget's current size.
     *
     * The count is derived purely from height — there is no configured message count, because the
     * size the user drags the widget to *is* the setting. A 4x1 widget shows one message (the
     * latest); growing it to 4x4 shows more, up to [RoomWidgetStore.MAX_MESSAGE_LIMIT].
     *
     * The constants are measured against what the widget actually draws, not guessed — an
     * over-estimate here is not free: it shows fewer rows than fit and leaves dead space at the
     * bottom at *every* widget size, which is exactly what a too-tall row estimate produced before.
     *
     * The floor is 1, not 5 — a one-row widget is a legitimate size, and a floor above what fits is
     * what forces content off the bottom edge.
     */
    @Composable
    private fun fittingMessageCount(): Int {
        val availableDp = (LocalSize.current.height.value - CHROME_HEIGHT_DP).coerceAtLeast(0f)
        val fits = (availableDp / ROW_HEIGHT_DP).toInt()
        return fits.coerceIn(RoomWidgetStore.MIN_MESSAGE_LIMIT, RoomWidgetStore.MAX_MESSAGE_LIMIT)
    }

    /**
     * Open the room — and optionally scroll to [eventId] — via exactly the intent contract
     * notifications use (`EnhancedNotificationDisplay.createRoomIntent`), so widget taps and
     * notification taps land through the same, already-tested navigation path.
     *
     * `from_notification` is deliberately false: that flag drives notification-specific freshness
     * and dismissal handling which does not apply here.
     */
    private fun openRoomAction(context: Context, roomId: String?, eventId: String? = null): androidx.glance.action.Action =
        androidx.glance.appwidget.action.actionStartActivity(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("room_id", roomId)
                putExtra("event_id", eventId)
                putExtra("direct_navigation", true)
                putExtra("from_notification", false)
                if (roomId != null && roomId.length > 1) {
                    data = android.net.Uri.parse(
                        "matrix:roomid/${roomId.substring(1)}" +
                            (eventId?.takeIf { it.length > 1 }?.let { "/e/${it.substring(1)}" } ?: ""),
                    )
                }
            },
        )

    companion object {
        private const val TAG = "RoomWidget"

        /**
         * Everything above the message list: the root Column's 12dp top+bottom padding (24), the
         * header row (a 24dp avatar, the tallest thing in it) and the 8dp spacer under it.
         */
        private const val CHROME_HEIGHT_DP = 24f + 24f + 8f

        /**
         * One message row.
         *
         * A row is 3dp+3dp padding around `max(avatar, text column)`. With the sender line that is
         * ~17dp (12sp) + ~20dp (14sp body) = 37; a continuation row is the 28dp avatar spacer. So
         * real rows land in the 34–43dp band and 40 sits in the middle — close enough that a full
         * widget looks full, and when it is wrong it is wrong by part of one row.
         */
        private const val ROW_HEIGHT_DP = 40f

        /**
         * Recomposition signal, bumped by [RoomWidgetUpdater.redraw] on every snapshot write.
         *
         * Glance only re-reads state that the composition consumes via `currentState`, so this key
         * is what makes an `updateAll()` actually show new data. It carries no meaning beyond
         * "something changed" — the data itself lives in [RoomWidgetStore], which the workers and
         * `SyncIngestor` can write synchronously from any thread.
         */
        val REVISION_KEY = intPreferencesKey("snapshot_revision")
    }
}

/** Manual refresh button. Marks the snapshot as refreshing, redraws, then does the real work. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val roomId = parameters[roomIdKey]?.takeIf { it.isNotBlank() } ?: return
        RoomWidgetUpdater.requestManualRefresh(context, roomId)
    }

    companion object {
        val roomIdKey = ActionParameters.Key<String>("room_id")
    }
}
