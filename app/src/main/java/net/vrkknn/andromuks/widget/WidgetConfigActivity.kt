package net.vrkknn.andromuks.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.vrkknn.andromuks.R
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.utils.RoomMetadataStore

/**
 * Room picker shown when a room widget is dropped on the home screen.
 *
 * Deliberately reads only [RoomMetadataStore] — the one SQLite-backed store — rather than the live
 * room list. The user may well be configuring a widget with the app force-stopped and the
 * WebSocket down, and a picker that needs a running sync to show anything would be useless exactly
 * when the widget is most wanted.
 */
class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Backing out must leave no widget behind, so the cancelled result is set up front — the
        // host reads it if the user presses back at any point.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val rooms = loadRooms()

        setContent {
            AndromuksTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RoomPicker(rooms = rooms, onPick = ::onRoomChosen)
                }
            }
        }
    }

    /** Rooms most recently active first — the same ordering the room list uses. */
    private fun loadRooms(): List<RoomChoice> = try {
        RoomMetadataStore.initialize(applicationContext)
        RoomMetadataStore.loadAll().values
            .map { RoomChoice(roomId = it.roomId, name = it.name?.takeIf { n -> n.isNotBlank() } ?: it.roomId, sortTs = it.sortTs) }
            .sortedByDescending { it.sortTs }
    } catch (e: Exception) {
        emptyList()
    }

    private fun onRoomChosen(choice: RoomChoice) {
        RoomWidgetStore.bind(
            context = applicationContext,
            appWidgetId = appWidgetId,
            roomId = choice.roomId,
            roomName = choice.name,
            messageLimit = RoomWidgetStore.DEFAULT_MESSAGE_LIMIT,
        )
        // Seed a placeholder so the widget paints its header immediately instead of flashing
        // "Tap to configure" until the first fetch lands.
        RoomWidgetStore.writeSnapshot(
            applicationContext,
            appWidgetId,
            RoomWidgetSnapshot.loading(choice.roomId, choice.name),
        )
        RoomWidgetUpdater.redraw(applicationContext)
        RoomWidgetRefreshWorker.enqueue(applicationContext, choice.roomId, expedited = true)

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

/** One selectable row in the picker. */
data class RoomChoice(val roomId: String, val name: String, val sortTs: Long)

@Composable
private fun RoomPicker(rooms: List<RoomChoice>, onPick: (RoomChoice) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, rooms) {
        if (query.isBlank()) {
            rooms
        } else {
            rooms.filter { it.name.contains(query, ignoreCase = true) || it.roomId.contains(query, ignoreCase = true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = stringResource(R.string.room_widget_config_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (rooms.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.room_widget_config_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text(stringResource(R.string.room_widget_config_search)) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.roomId }) { room ->
                RoomRow(room = room, onPick = onPick)
            }
        }
    }
}

@Composable
private fun RoomRow(room: RoomChoice, onPick: (RoomChoice) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(room) }
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = room.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = room.roomId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
