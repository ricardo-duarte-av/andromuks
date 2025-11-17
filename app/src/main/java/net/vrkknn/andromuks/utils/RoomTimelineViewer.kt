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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
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

private data class TimelineColumnSpec(val title: String, val width: Dp)

private data class TimelineEventRow(
    val index: Int,
    val timestamp: Long,
    val formattedDateTime: String,
    val username: String,
    val content: String,
    val relations: String
)

/**
 * Extract timestamp from event JSON (checks both timestamp and origin_server_ts)
 */
private fun extractTimestamp(rawJson: String, fallbackTimestamp: Long): Long {
    if (fallbackTimestamp > 0) {
        return fallbackTimestamp
    }
    
    return try {
        val json = JSONObject(rawJson)
        // Check for both "timestamp" and "origin_server_ts" since DB stores origin_server_ts
        val timestamp = json.optLong("timestamp", 0L).takeIf { it > 0 }
            ?: json.optLong("origin_server_ts", 0L)
        timestamp.takeIf { it > 0 } ?: fallbackTimestamp
    } catch (e: Exception) {
        fallbackTimestamp
    }
}

/**
 * Extract text content from event JSON
 */
private fun extractTextContent(rawJson: String): String {
    return try {
        val json = JSONObject(rawJson)
        
        // Try decrypted content first (for encrypted messages)
        val decrypted = json.optJSONObject("decrypted")
        val content = decrypted ?: json.optJSONObject("content")
        
        if (content == null) {
            return ""
        }
        
        // Check if this is an edit event (m.replace) - use m.new_content
        val relatesTo = content.optJSONObject("m.relates_to")
        val isEdit = relatesTo?.optString("rel_type") == "m.replace"
        val actualContent = if (isEdit) {
            content.optJSONObject("m.new_content") ?: content
        } else {
            content
        }
        
        // Try body field first (plain text)
        val body = actualContent.optString("body", "")
        if (body.isNotBlank()) {
            return if (isEdit) "[EDITED] $body" else body
        }
        
        // Try formatted_body (HTML) - extract text from it
        val formattedBody = actualContent.optString("formatted_body", "")
        if (formattedBody.isNotBlank()) {
            // Simple HTML tag removal for preview
            val text = formattedBody
                .replace(Regex("<[^>]*>"), " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (isEdit) "[EDITED] $text" else text
        }
        
        // For media messages, show filename or msgtype
        val msgtype = actualContent.optString("msgtype", "")
        val filename = actualContent.optString("filename", "")
        when {
            filename.isNotBlank() -> "[$msgtype] $filename"
            msgtype.isNotBlank() -> "[$msgtype]"
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }
}

/**
 * Extract relations/thread/edit info from event
 */
private fun extractRelationsInfo(entity: EventEntity, rawJson: String): String {
    val parts = mutableListOf<String>()
    
    // Check for edit (m.replace)
    try {
        val json = JSONObject(rawJson)
        val content = json.optJSONObject("content")
        val relatesTo = content?.optJSONObject("m.relates_to")
        if (relatesTo != null) {
            val relType = relatesTo.optString("rel_type", "")
            when (relType) {
                "m.replace" -> {
                    val eventId = relatesTo.optString("event_id", "")
                    if (eventId.isNotBlank()) {
                        parts.add("EDIT:$eventId")
                    }
                }
                "m.thread" -> {
                    val eventId = relatesTo.optString("event_id", "")
                    if (eventId.isNotBlank()) {
                        parts.add("THREAD:$eventId")
                    }
                }
                "m.annotation" -> {
                    // Reaction
                    val key = relatesTo.optString("key", "")
                    if (key.isNotBlank()) {
                        parts.add("REACTION:$key")
                    }
                }
                else -> {
                    val eventId = relatesTo.optString("event_id", "")
                    if (eventId.isNotBlank() && relType.isNotBlank()) {
                        parts.add("$relType:$eventId")
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Ignore parse errors
    }
    
    // Check thread root from entity
    entity.threadRootEventId?.let {
        if (!parts.contains("THREAD:$it")) {
            parts.add("THREAD_ROOT:$it")
        }
    }
    
    // Check relates to from entity
    entity.relatesToEventId?.let {
        if (!parts.any { it.startsWith("EDIT:") || it.startsWith("THREAD:") }) {
            parts.add("RELATES_TO:$it")
        }
    }
    
    return parts.joinToString(", ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomTimelineViewerScreen(
    roomId: String,
    appViewModel: AppViewModel,
    navController: NavController
) {
    val scope = rememberCoroutineScope()
    var limitText by remember { mutableStateOf("500") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var totalCount by remember { mutableStateOf<Int?>(null) }
    var events by remember { mutableStateOf<List<TimelineEventRow>>(emptyList()) }

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
                
                // Extract timestamps and sort by timestamp descending (most recent first)
                // We need to extract timestamps from rawJson for encrypted events
                val entitiesWithTimestamps = entities.map { entity ->
                    val extractedTimestamp = extractTimestamp(entity.rawJson, entity.timestamp)
                    Pair(entity, extractedTimestamp)
                }
                
                val sortedEntities = entitiesWithTimestamps.sortedWith(
                    compareByDescending<Pair<EventEntity, Long>> { (_, timestamp) ->
                        timestamp.takeIf { it > 0 } ?: Long.MIN_VALUE
                    }.thenByDescending { (entity, _) -> entity.timelineRowId }
                )
                
                events = sortedEntities.mapIndexed { index, (entity, extractedTimestamp) ->
                    val content = extractTextContent(entity.rawJson)
                    val relations = extractRelationsInfo(entity, entity.rawJson)
                    
                    // Extract username from sender
                    val username = entity.sender.split(":").firstOrNull()?.removePrefix("@") ?: entity.sender
                    
                    TimelineEventRow(
                        index = index + 1,
                        timestamp = extractedTimestamp,
                        formattedDateTime = if (extractedTimestamp > 0) {
                            formatter.format(Date(extractedTimestamp))
                        } else {
                            ""
                        },
                        username = username,
                        content = content.take(200), // Limit content preview
                        relations = relations
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
            TimelineColumnSpec("#", 60.dp),
            TimelineColumnSpec("Date/Time", 180.dp),
            TimelineColumnSpec("Username", 180.dp),
            TimelineColumnSpec("Content", 400.dp),
            TimelineColumnSpec("Relations/Thread/Edit", 400.dp)
        )
    }
    val tableWidth = remember(columns) {
        columns.fold(0.dp) { acc, spec -> acc + spec.width }
    }
    val horizontalScroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room Timeline Viewer") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val total = totalCount
                    if (total != null) {
                        Text(
                            text = "Total: $total",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 16.dp)
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
    }
}

@Composable
private fun TableHeader(columns: List<TimelineColumnSpec>) {
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
private fun TableRow(row: TimelineEventRow, columns: List<TimelineColumnSpec>) {
    val rowValues = listOf(
        row.index.toString(),
        row.formattedDateTime,
        row.username,
        row.content,
        row.relations
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

