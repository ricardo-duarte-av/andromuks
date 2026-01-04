@file:Suppress("DEPRECATION")

package net.vrkknn.andromuks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnprocessedEventsScreen(
    appViewModel: AppViewModel,
    navController: NavController
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AppViewModel.UnprocessedEventLogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun load() {
        coroutineScope.launch {
            isLoading = true
            entries = appViewModel.getUnprocessedEvents()
            isLoading = false
        }
    }

    fun clear() {
        coroutineScope.launch {
            isLoading = true
            appViewModel.clearUnprocessedEvents()
            entries = emptyList()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Unprocessed events") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (entries.isNotEmpty() && !isLoading) {
                    IconButton(onClick = { clear() }) {
                        Icon(imageVector = Icons.Filled.Delete, contentDescription = "Clear log")
                    }
                }
            }
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No unprocessed events",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries) { entry ->
                    UnprocessedEventCard(entry = entry, context = context)
                }
            }
        }
    }
}

@Composable
private fun UnprocessedEventCard(
    entry: AppViewModel.UnprocessedEventLogEntry,
    context: Context
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(entry.createdAt) { dateFormat.format(Date(entry.createdAt)) }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Event ID: ${entry.eventId}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            entry.roomId?.let {
                Text(
                    text = "Room: $it",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = "Reason: ${entry.reason}",
                style = MaterialTheme.typography.bodyMedium
            )
            entry.source?.let {
                Text(
                    text = "Source: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.rawJson,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { copyToClipboard("Event JSON", entry.rawJson) }) {
                    Text("Copy JSON")
                }
            }
        }
    }
}

