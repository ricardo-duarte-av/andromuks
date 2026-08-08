package net.vrkknn.andromuks.widget

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
            null
        }
        val snapshot = appWidgetId?.let { RoomWidgetStore.readSnapshot(context, it) }
        val roomId = appWidgetId?.let { RoomWidgetStore.roomIdFor(context, it) }
        val configuredName = appWidgetId?.let { RoomWidgetStore.configuredRoomName(context, it) }
        val limit = appWidgetId?.let { RoomWidgetStore.messageLimit(context, it) }
            ?: RoomWidgetStore.DEFAULT_MESSAGE_LIMIT

        provideContent {
            GlanceTheme {
                WidgetBody(
                    snapshot = snapshot ?: roomId?.let {
                        RoomWidgetSnapshot.loading(it, configuredName ?: it)
                    },
                    configuredLimit = limit,
                )
            }
        }
    }

    @Composable
    private fun WidgetBody(snapshot: RoomWidgetSnapshot?, configuredLimit: Int) {
        val context = LocalContext.current
        // The configured limit is a ceiling; what actually fits is decided by height, so a resized
        // widget shows as much as it can rather than a fixed count with dead space or clipping.
        val visibleCount = fittingMessageCount(configuredLimit)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(openRoomAction(context, snapshot?.roomId)),
        ) {
            WidgetHeader(snapshot)
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
    private fun WidgetHeader(snapshot: RoomWidgetSnapshot?) {
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
                            actionParametersOf(RefreshWidgetAction.roomIdKey to (snapshot?.roomId ?: "")),
                        ),
                    ),
            )
        }
    }

    @Composable
    private fun MessageList(snapshot: RoomWidgetSnapshot, visibleCount: Int) {
        val context = LocalContext.current
        val messages = snapshot.messages.takeLast(visibleCount)
        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            itemsIndexed(messages, itemId = { _, item -> item.eventId.hashCode().toLong() }) { index, message ->
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
     * How many rows fit in the current widget height, capped by the user's configured maximum.
     *
     * Each row is roughly 44 dp (two text lines plus padding) and the header costs ~40 dp.
     */
    @Composable
    private fun fittingMessageCount(configuredLimit: Int): Int {
        val availableDp = (LocalSize.current.height.value - HEADER_HEIGHT_DP).coerceAtLeast(0f)
        val fits = (availableDp / ROW_HEIGHT_DP).toInt()
        return fits.coerceIn(RoomWidgetStore.MIN_MESSAGE_LIMIT, configuredLimit.coerceAtLeast(RoomWidgetStore.MIN_MESSAGE_LIMIT))
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

    private companion object {
        const val HEADER_HEIGHT_DP = 40f
        const val ROW_HEIGHT_DP = 44f
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
