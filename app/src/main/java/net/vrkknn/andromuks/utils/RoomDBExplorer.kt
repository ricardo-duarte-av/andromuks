package net.vrkknn.andromuks.utils

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.database.entities.EventEntity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ColumnSpec(val title: String, val width: Dp)

private data class RoomDbEventRow(
    val index: Int,
    val timelineRowId: Long,
    val type: String,
    val sender: String,
    val timestamp: Long,
    val formattedTimestamp: String,
    val eventId: String,
    val relatesTo: String?,
    val threadRoot: String?,
    val decryptedType: String?,
    val aggregatedReactions: String?,
    val isRedaction: Boolean,
    val rawPreview: String
)

private fun String.toJsonString(): String {
    return JSONObject.quote(this)
}

private fun String?.toJsonOrNull(): String = this?.toJsonString() ?: "null"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDBExplorerScreen(
    roomId: String,
    appViewModel: AppViewModel,
    navController: NavController
) {
    val scope = rememberCoroutineScope()
    var limitText by remember { mutableStateOf("500") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var totalCount by remember { mutableStateOf<Int?>(null) }
    var events by remember { mutableStateOf<List<RoomDbEventRow>>(emptyList()) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    val formatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    fun refresh(fromUser: Boolean = false) {
        val parsedLimit = limitText.toIntOrNull()
        val sanitizedLimit = parsedLimit?.coerceIn(1, 5000) ?: 500
        if (parsedLimit == null || parsedLimit != sanitizedLimit) {
            limitText = sanitizedLimit.toString()
        }
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val entities = appViewModel.getRoomEventsFromDb(roomId, sanitizedLimit)
                val count = appViewModel.getRoomEventCountFromDb(roomId)
                totalCount = count
                val sortedEntities = entities.sortedWith(
                    compareByDescending<EventEntity> { entity ->
                        if (entity.timestamp > 0) entity.timestamp else Long.MIN_VALUE
                    }.thenByDescending { it.timelineRowId }
                )
                events = sortedEntities.mapIndexed { index, entity ->
                    RoomDbEventRow(
                        index = index + 1,
                        timelineRowId = entity.timelineRowId,
                        type = entity.type,
                        sender = entity.sender,
                        timestamp = entity.timestamp,
                        formattedTimestamp = if (entity.timestamp > 0) {
                            formatter.format(Date(entity.timestamp))
                        } else {
                            ""
                        },
                        eventId = entity.eventId,
                        relatesTo = entity.relatesToEventId,
                        threadRoot = entity.threadRootEventId,
                        decryptedType = entity.decryptedType,
                        aggregatedReactions = entity.aggregatedReactionsJson,
                        isRedaction = entity.isRedaction,
                        rawPreview = entity.rawJson
                            .replace("\n", " ")
                            .replace("\\s+".toRegex(), " ")
                            .take(400)
                    )
                }
                if (fromUser && entities.isEmpty()) {
                    errorMessage = "Query returned no events for the current limit."
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load events: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(roomId) {
        refresh()
    }

    val columns = remember {
        listOf(
            ColumnSpec("#", 56.dp),
            ColumnSpec("Timeline Row ID", 140.dp),
            ColumnSpec("Type", 180.dp),
            ColumnSpec("Sender", 220.dp),
            ColumnSpec("Timestamp", 160.dp),
            ColumnSpec("Local Time", 200.dp),
            ColumnSpec("Event ID", 320.dp),
            ColumnSpec("Relates To", 320.dp),
            ColumnSpec("Thread Root", 320.dp),
            ColumnSpec("Decrypted Type", 180.dp),
            ColumnSpec("Aggregated Reactions", 220.dp),
            ColumnSpec("Redaction", 120.dp),
            ColumnSpec("Raw Preview", 480.dp)
        )
    }
    val tableWidth = remember(columns) {
        columns.fold(0.dp) { acc, spec -> acc + spec.width }
    }
    val horizontalScroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room DB Explorer") },
                actions = {
                    val total = totalCount
                    if (total != null) {
                        Text(
                            text = "Total: $total",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                    IconButton(
                        enabled = events.isNotEmpty(),
                        onClick = {
                            val export = events.joinToString(
                                separator = "\n",
                                prefix = "RoomDBExplorer Export (room=$roomId, rows=${events.size}):\n"
                            ) { row ->
                                buildString {
                                    append("{\"index\":${row.index},")
                                    append("\"event_id\":${row.eventId.toJsonString()},")
                                    append("\"timeline_rowid\":${row.timelineRowId},")
                                    append("\"type\":${row.type.toJsonString()},")
                                    append("\"timestamp\":${row.timestamp},")
                                    append("\"sender\":${row.sender.toJsonString()},")
                                    append("\"relates_to\":${row.relatesTo.toJsonOrNull()},")
                                    append("\"thread_root\":${row.threadRoot.toJsonOrNull()},")
                                    append("\"aggregated_reactions\":${row.aggregatedReactions ?: "null"},")
                                    append("\"raw_json\":${row.rawPreview.toJsonString()}}")
                                }
                            }
                            android.util.Log.d("RoomDBExplorer", export)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Export Visible Rows"
                        )
                    }
                    IconButton(
                        enabled = !isDeleting,
                        onClick = { showDeleteConfirmation = true }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete Room Data",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Inspecting room: $roomId",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it.take(5) },
                    label = { Text("Max rows") },
                    singleLine = true,
                    modifier = Modifier.width(140.dp)
                )
                Button(
                    onClick = { refresh(fromUser = true) },
                    enabled = !isLoading
                ) {
                    Text("Refresh")
                }
                TextButton(
                    onClick = {
                        limitText = "500"
                        refresh(fromUser = true)
                    },
                    enabled = !isLoading
                ) {
                    Text("Reset")
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!isLoading && events.isEmpty() && errorMessage == null) {
                Text(
                    text = "No events available for this room.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScroll)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .width(tableWidth)
                        .fillMaxSize()
                ) {
                    item {
                        TableHeader(columns = columns)
                    }
                    items(events) { row ->
                        SelectionContainer {
                            Column {
                                TableRow(row = row, columns = columns)
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.material3.Divider()
                            }
                        }
                    }
                }
            }
        }
        
        // Delete confirmation dialog
        if (showDeleteConfirmation) {
            AlertDialog(
                onDismissRequest = { if (!isDeleting) showDeleteConfirmation = false },
                title = { Text("Delete Room Data") },
                text = { 
                    Text("Are you sure you want to delete ALL data for this room? This includes events, state, receipts, reactions, and all cached data. This action cannot be undone.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isDeleting = true
                            scope.launch {
                                try {
                                    appViewModel.deleteAllRoomData(roomId)
                                    showDeleteConfirmation = false
                                    // Navigate back to room list
                                    navController.popBackStack()
                                } catch (e: Exception) {
                                    errorMessage = "Failed to delete room data: ${e.message}"
                                    android.util.Log.e("RoomDBExplorer", "Error deleting room data", e)
                                } finally {
                                    isDeleting = false
                                }
                            }
                        },
                        enabled = !isDeleting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(if (isDeleting) "Deleting..." else "Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirmation = false },
                        enabled = !isDeleting
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun TableHeader(columns: List<ColumnSpec>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            columns.forEach { column ->
                Text(
                    text = column.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .width(column.width)
                        .padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun TableRow(row: RoomDbEventRow, columns: List<ColumnSpec>) {
    val rowValues = listOf(
        row.index.toString(),
        row.timelineRowId.toString(),
        row.type,
        row.sender,
        if (row.timestamp > 0) row.timestamp.toString() else "",
        row.formattedTimestamp,
        row.eventId,
        row.relatesTo ?: "",
        row.threadRoot ?: "",
        row.decryptedType ?: "",
        row.aggregatedReactions ?: "",
        if (row.isRedaction) "Yes" else "",
        row.rawPreview
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        columns.forEachIndexed { index, column ->
            val text = rowValues.getOrNull(index) ?: ""
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .width(column.width)
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

