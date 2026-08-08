package net.vrkknn.andromuks.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Host-facing entry point for the room widget.
 *
 * Glance handles the drawing; this class exists for the AppWidget lifecycle callbacks, which are
 * where the widget's *data* lifecycle has to be kept in step:
 *
 * - **added / host restarted** → refresh, because a snapshot written before a reboot is stale.
 * - **resized** → refresh is unnecessary (render already adapts), but Glance must redraw.
 * - **deleted** → drop the binding, or the room stays in
 *   [RoomWidgetStore.boundRoomIds] forever and `SyncIngestor` keeps parsing events for a widget
 *   that no longer exists.
 */
class RoomWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoomWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Also prunes bindings whose widget vanished without an onDeleted (restore, reinstall).
        RoomWidgetStore.pruneOrphans(context)
        val rooms = appWidgetIds.toList().mapNotNull { RoomWidgetStore.roomIdFor(context, it) }.distinct()
        if (rooms.isEmpty()) return
        Log.i(TAG, "onUpdate: refreshing ${rooms.size} room widget(s)")
        rooms.forEach { RoomWidgetUpdater.requestRefresh(context, it, reason = "app-widget-update") }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // Size changed only: the snapshot is still valid, the layout just has to be recomputed.
        RoomWidgetUpdater.redraw(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { RoomWidgetStore.unbind(context, it) }
        Log.i(TAG, "onDeleted: released ${appWidgetIds.size} widget binding(s)")
    }

    private companion object {
        const val TAG = "RoomWidgetReceiver"
    }
}
