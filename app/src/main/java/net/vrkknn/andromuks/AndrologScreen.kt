@file:Suppress("DEPRECATION")

package net.vrkknn.andromuks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
fun AndrologScreen(
    navController: NavController
) {
    // refreshTrigger forces re-read of the in-memory log (e.g. after clearing).
    var refreshTrigger by remember { mutableStateOf(0) }
    val entries = remember(refreshTrigger) { Androlog.getEntries() }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            scope.launch { snackbarHostState.showSnackbar("Export cancelled") }
            return@rememberLauncherForActivityResult
        }
        try {
            val exportText = buildAndrologExportText(entries)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(exportText)
            }
            scope.launch { snackbarHostState.showSnackbar("Androlog exported") }
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Failed to export Androlog") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Androlog") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            Androlog.clear()
                            refreshTrigger++
                            scope.launch { snackbarHostState.showSnackbar("Androlog cleared") }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Clear log"
                        )
                    }
                    TextButton(
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            exportLauncher.launch("andromuks_androlog_$stamp.txt")
                        }
                    ) {
                        Text("Export")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No Androlog entries yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Cherry-picked events logged via Androlog(...) will appear here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries.reversed()) { entry ->
                        AndrologEntryCard(entry = entry)
                    }
                }
            }
        }
    }
}

private fun buildAndrologExportText(entries: List<Androlog.Entry>): String {
    val lineDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val now = Date()
    val builder = StringBuilder()

    builder.append("Andromuks Androlog\n")
    builder.append("Exported at: ${lineDateFormat.format(now)}\n")
    builder.append("Total entries: ${entries.size}\n")
    builder.append("\n")

    entries.forEach { entry ->
        val timestamp = lineDateFormat.format(Date(entry.timestamp))
        builder.append("$timestamp | ${entry.category} | ${entry.text}\n")
    }

    return builder.toString()
}

@Composable
fun AndrologEntryCard(entry: Androlog.Entry) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val formattedTime = dateFormat.format(Date(entry.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.category.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = entry.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
