package net.vrkknn.andromuks.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.glance.layout.height
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
        val visible = visibleMessages(snapshot?.messages.orEmpty())

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

                else -> MessageList(snapshot, visible)
            }
        }
    }

    @Composable
    private fun WidgetHeader(snapshot: RoomWidgetSnapshot?, roomId: String?) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val roomAvatar = remember(snapshot?.roomAvatarPath) {
                snapshot?.roomAvatarPath?.let { decodeAvatarFile(it) }
            }
            AvatarImage(bitmap = roomAvatar, sizeDp = 24)
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
    private fun MessageList(snapshot: RoomWidgetSnapshot, messages: List<WidgetMessage>) {
        val context = LocalContext.current
        // Decode each distinct avatar ONCE and share the Bitmap instance across every row that
        // uses it. RemoteViews keeps a BitmapCache keyed on the bitmap itself, so a conversation
        // between two people costs two bitmaps in the IPC transaction no matter how many messages
        // are shown — which is what makes a 30-message widget affordable at all. Decoding per row
        // would instead scale the transaction with the message count and eventually exceed the cap,
        // killing the update outright rather than degrading.
        val avatars = remember(messages) { decodeAvatars(messages) }

        // Rows are grouped into nested Columns of at most MAX_COLUMN_CHILDREN.
        //
        // A Glance Column maps onto a generated RemoteViews layout with a FIXED number of child
        // slots — ten — and exceeding it does not wrap or scroll: the translator drops the
        // surplus and logs "Column container cannot have more than 10 elements". It drops the
        // *last* children, so an 14-row widget silently lost its four newest messages and showed a
        // gap where they belonged.
        //
        // Nesting sidesteps it: each chunk is one child of the outer Column, so capacity becomes
        // MAX_COLUMN_CHILDREN² = 100 rows, comfortably past MAX_MESSAGE_LIMIT. Flattening this back
        // into a single Column will silently truncate again.
        // Belt and braces: nesting affords MAX_COLUMN_CHILDREN² rows, and truncation past that
        // would be silent again. MAX_MESSAGE_LIMIT is well inside it today.
        val chunks = messages.takeLast(MAX_COLUMN_CHILDREN * MAX_COLUMN_CHILDREN).chunked(MAX_COLUMN_CHILDREN)
        Column(modifier = GlanceModifier.fillMaxSize()) {
            chunks.forEachIndexed { chunkIndex, chunk ->
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    chunk.forEachIndexed { indexInChunk, message ->
                        // Sender grouping is decided against the *whole* visible list, not the
                        // chunk, or the first row of each chunk would redundantly repeat its
                        // sender — and [visibleMessages] budgets against this exact rule.
                        val index = chunkIndex * MAX_COLUMN_CHILDREN + indexInChunk
                        MessageRow(
                            message = message,
                            avatar = message.senderAvatarPath?.let { avatars[it] },
                            showSender = showsSender(messages, index),
                            onClick = openRoomAction(context, snapshot.roomId, message.eventId),
                        )
                    }
                }
            }
        }
    }

    /**
     * Decode the distinct sender avatars referenced by [messages].
     *
     * Capped at [MAX_DISTINCT_AVATARS]: senders beyond that render without a picture rather than
     * risking the RemoteViews transaction limit. With per-sender de-duplication, reaching the cap
     * needs that many *different* people in one screenful, which is rare.
     */
    private fun decodeAvatars(messages: List<WidgetMessage>): Map<String, Bitmap> = messages
        .mapNotNull { it.senderAvatarPath }
        .distinct()
        .take(MAX_DISTINCT_AVATARS)
        .mapNotNull { path -> decodeAvatarFile(path)?.let { path to it } }
        .toMap()

    private fun decodeAvatarFile(path: String): Bitmap? = try {
        File(path).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not decode widget avatar $path: ${e.message}")
        null
    }

    /**
     * One message row, at a **fixed** height.
     *
     * Pinning the height is what lets [visibleMessages] be exact rather than approximate. Left to
     * wrap, a row is anywhere from ~26dp (a continuation row) to ~63dp (a sender row whose body
     * wraps onto a second line), and no single average survives that spread — guess low and rows
     * render past the bottom edge, guess high and the widget wastes a row of space. The body is
     * therefore capped at one ellipsized line.
     */
    @Composable
    private fun MessageRow(message: WidgetMessage, avatar: Bitmap?, showSender: Boolean, onClick: androidx.glance.action.Action) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height((if (showSender) SENDER_ROW_HEIGHT_DP else CONTINUATION_ROW_HEIGHT_DP).dp)
                .padding(vertical = 3.dp)
                .clickable(onClick),
        ) {
            if (showSender) {
                AvatarImage(bitmap = avatar, sizeDp = 28)
            } else {
                // width only: `size` would also pin the height, keeping continuation rows as tall
                // as an avatar they do not draw.
                Spacer(GlanceModifier.width(28.dp))
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
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                )
            }
        }
    }

/**
     * Draw a pre-rendered circular avatar, or reserve its space if there is none.
     *
     * The bitmap is decoded by the caller so it can be shared between rows — see [decodeAvatars].
     * The file itself was already scaled to ~96px by [RoomWidgetAvatars], so decoding is cheap and
     * involves no resize.
     */
    @Composable
    private fun AvatarImage(bitmap: Bitmap?, sizeDp: Int) {
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
     * Rows are two different fixed heights, so this cannot be a division: a run of messages from
     * one sender packs into short continuation rows where alternating senders need tall ones, and
     * the same widget therefore holds a different *number* of messages depending on who sent them.
     * Averaging over that spread is what made rows render past the bottom edge.
     *
     * So walk newest-to-oldest and spend the height budget exactly. The subtlety is that adding an
     * older message can make the message after it *cheaper*: whichever message is drawn first
     * always shows its sender, so prepending a same-sender message downgrades the previous first
     * row from a sender row to a continuation row and hands some budget back.
     *
     * Returns newest-last, matching render order.
     */
    @Composable
    private fun visibleMessages(all: List<WidgetMessage>): List<WidgetMessage> {
        if (all.isEmpty()) return emptyList()
        val budget = (LocalSize.current.height.value - CHROME_HEIGHT_DP).coerceAtLeast(0f)

        val picked = mutableListOf<WidgetMessage>() // newest first while building
        var used = 0f
        for (candidate in all.asReversed().take(RoomWidgetStore.MAX_MESSAGE_LIMIT)) {
            // A newly prepended row always draws its sender, hence the full height...
            val previousFirst = picked.lastOrNull()
            // ...but it may demote the row that was first until now.
            val demotesPrevious = previousFirst != null && previousFirst.senderId == candidate.senderId
            val cost = if (demotesPrevious) CONTINUATION_ROW_HEIGHT_DP else SENDER_ROW_HEIGHT_DP
            if (used + cost > budget && picked.isNotEmpty()) break
            used += cost
            picked.add(candidate)
        }
        // Always show at least the latest message: a widget too short for even one row should still
        // say something rather than render an empty card.
        return picked.reversed()
    }

    /** The render-time rule [visibleMessages] budgets against: first row, or the sender changed. */
    private fun showsSender(messages: List<WidgetMessage>, index: Int): Boolean = index == 0 ||
        messages[index - 1].senderId != messages[index].senderId

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
         * A row that draws the sender: 3dp+3dp padding around `max(28dp avatar, 17dp name line +
         * 20dp body line)`. Pinned via `GlanceModifier.height`, so this is exact, not an estimate.
         */
        private const val SENDER_ROW_HEIGHT_DP = 44f

        /** A continuation row: padding around a single 20dp body line, with no avatar and no name. */
        private const val CONTINUATION_ROW_HEIGHT_DP = 26f

        /**
         * Ceiling on distinct avatar bitmaps in one update.
         *
         * Every bitmap rides the RemoteViews transaction, which is hard-capped and drops the whole
         * update when exceeded rather than degrading. At ~36KB per 96px avatar this leaves plenty
         * of headroom, and because [decodeAvatars] de-duplicates by file, hitting it requires that
         * many *different* senders on screen at once.
         */
        private const val MAX_DISTINCT_AVATARS = 16

        /**
         * Children a single Glance container can hold.
         *
         * Not a style choice — a hard limit of the generated RemoteViews layouts. Past it the
         * translator *silently drops the surplus* (logging "Column container cannot have more than
         * 10 elements") rather than scrolling or wrapping, and it drops the last children, so the
         * newest messages are the ones lost. [MessageList] nests containers to get around it.
         */
        private const val MAX_COLUMN_CHILDREN = 10

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
