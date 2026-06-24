package net.vrkknn.andromuks

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.utils.JsonVisualTransformation
import net.vrkknn.andromuks.utils.highlightJson
import net.vrkknn.andromuks.utils.horizontalScrollbar
import net.vrkknn.andromuks.utils.rememberJsonHighlightColors
import net.vrkknn.andromuks.utils.verticalScrollbar
import org.json.JSONObject

/**
 * Browse and edit Matrix client account data keys cached from sync (m.direct, preferences, etc.).
 *
 * Tapping a key renders its JSON with syntax highlighting. The edit FAB opens the same JSON in a
 * highlighted editor; the save FAB validates the JSON and pushes it to the server via
 * `set_account_data` (sending the inner `content` object).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDataVisualizerScreen(appViewModel: AppViewModel, navController: NavController) {
    var refreshTick by remember { mutableStateOf(0) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var editLoading by remember { mutableStateOf(false) }
    var parseError by remember { mutableStateOf<String?>(null) }

    val entries = remember(refreshTick) {
        AccountDataCache.getAllAccountData().toSortedMap()
    }
    val highlightColors = rememberJsonHighlightColors()

    fun leaveDetail() {
        selectedKey = null
        editing = false
        parseError = null
    }

    // Pretty-printing huge keys (e.g. m.push_rules) is expensive; do it off the main thread so the
    // editor doesn't stall on open. While it runs, editLoading drives a spinner.
    LaunchedEffect(editing, selectedKey, refreshTick) {
        val key = selectedKey
        if (editing && key != null) {
            editLoading = true
            editText = withContext(Dispatchers.Default) { formatAccountDataJson(entries[key]) }
            editLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            selectedKey == null -> "Account data"
                            editing -> "Editing: $selectedKey"
                            else -> "Account Data: $selectedKey"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                editing -> {
                                    editing = false
                                    parseError = null
                                }
                                selectedKey != null -> leaveDetail()
                                else -> navController.popBackStack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (selectedKey == null) {
                        IconButton(onClick = { refreshTick++ }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh list"
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            val key = selectedKey
            if (key != null) {
                if (editing) {
                    ExtendedFloatingActionButton(
                        text = { Text("Save") },
                        icon = { Icon(Icons.Filled.Save, contentDescription = null) },
                        onClick = {
                            val result = saveAccountDataJson(appViewModel, key, editText)
                            if (result == null) {
                                editing = false
                                parseError = null
                                refreshTick++
                            } else {
                                parseError = result
                            }
                        }
                    )
                } else {
                    ExtendedFloatingActionButton(
                        text = { Text("Edit") },
                        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            parseError = null
                            editLoading = true
                            editing = true
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        val key = selectedKey
        when {
            key == null -> AccountDataKeyList(entries, innerPadding) { selectedKey = it }
            editing && editLoading -> LoadingBox(innerPadding)
            editing -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Edit the JSON below. On save it is validated, then the content field is " +
                        "pushed to the server via set_account_data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                parseError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                val borderColor = if (parseError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                }
                // The visual transformation re-highlights synchronously on every keystroke, which
                // is too slow for very large keys. Above the threshold, drop live highlighting so
                // typing stays responsive (the read-only viewer still shows it highlighted).
                val transformation = if (editText.length <= LIVE_HIGHLIGHT_MAX_CHARS) {
                    JsonVisualTransformation(highlightColors)
                } else {
                    VisualTransformation.None
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        .verticalScrollbar(vScroll, scrollbarColor)
                        .horizontalScrollbar(hScroll, scrollbarColor)
                ) {
                    BasicTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            // verticalScroll + horizontalScroll give the field infinite
                            // constraints in both axes, so the text never wraps and the box
                            // scrolls instead.
                            .verticalScroll(vScroll)
                            .horizontalScroll(hScroll),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = transformation
                    )
                }
            }
            else -> {
                // Pretty-print + highlight off the main thread, split into lines. A single Text
                // holding the whole AnnotatedString would freeze the UI thread for seconds on huge
                // keys (m.push_rules) — its layout cost is O(length). Rendering one Text per line in
                // a LazyColumn keeps only the visible lines laid out.
                val lines by produceState<List<AnnotatedString>?>(null, key, refreshTick, highlightColors) {
                    value = withContext(Dispatchers.Default) {
                        formatAccountDataJson(entries[key])
                            .split("\n")
                            .map { highlightJson(it, highlightColors) }
                    }
                }
                val current = lines
                if (current == null) {
                    LoadingBox(innerPadding)
                } else {
                    val listState = rememberLazyListState()
                    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScrollbar(listState, scrollbarColor),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(current.size) { i ->
                                Text(
                                    text = current[i],
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Live JSON highlighting in the editor is dropped above this size to keep typing responsive. */
private const val LIVE_HIGHLIGHT_MAX_CHARS = 40_000

@Composable
private fun LoadingBox(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AccountDataKeyList(
    entries: Map<String, JSONObject>,
    innerPadding: PaddingValues,
    onSelect: (String) -> Unit
) {
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
        ) {
            Text(
                text = "No account data in cache",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Open the app and complete sync so account_data (m.direct, preferences, etc.) is loaded, then tap refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "${entries.size} key${if (entries.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(entries.keys.toList(), key = { it }) { key ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(key) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

private fun formatAccountDataJson(obj: JSONObject?): String {
    if (obj == null) return "(null)"
    return try {
        obj.toString(2)
    } catch (_: Exception) {
        obj.toString()
    }
}

/**
 * Validate [text] as JSON and, if valid, push it to the server for account_data key [type].
 * The inner `content` object is sent via set_account_data (falling back to the whole object when
 * there is no `content` field). Returns null on success, or a human-readable error message.
 */
private fun saveAccountDataJson(appViewModel: AppViewModel, type: String, text: String): String? {
    val parsed = try {
        JSONObject(text)
    } catch (e: Exception) {
        return "Invalid JSON: ${e.message}"
    }
    val content = parsed.optJSONObject("content") ?: parsed
    return try {
        appViewModel.setAccountDataRaw(type, content)
        null
    } catch (e: Exception) {
        "Failed to save: ${e.message}"
    }
}
