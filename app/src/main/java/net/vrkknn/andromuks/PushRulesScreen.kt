package net.vrkknn.andromuks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.utils.NotificationPreset
import net.vrkknn.andromuks.utils.PushAction
import net.vrkknn.andromuks.utils.PushRule
import net.vrkknn.andromuks.utils.PushRuleKind
import net.vrkknn.andromuks.utils.actionsForPreset
import org.json.JSONArray
import org.json.JSONObject

/**
 * Global push rules editor. A landing page offers one button per rule kind
 * (override/content/room/sender/underride); selecting a kind opens a lazily-rendered list of just
 * that kind's rules (some kinds hold 150+ rules, so rendering everything at once was far too heavy).
 * Each rule is a smart card with preset chips, a raw-JSON escape hatch, and an add-rule flow.
 * Reads/writes [AppViewModel.pushRuleset] via the push-rule forwarders (see [PushRulesCoordinator]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushRulesScreen(
    appViewModel: AppViewModel,
    navController: NavController
) {
    val ruleset = appViewModel.pushRuleset
    var selectedKind by remember { mutableStateOf<PushRuleKind?>(null) }
    var addKind by remember { mutableStateOf<PushRuleKind?>(null) }

    // System-back: when viewing a kind, return to the kind picker instead of leaving the screen.
    BackHandler(enabled = selectedKind != null) { selectedKind = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedKind?.let { "${it.displayName} Rules" } ?: "Push Rules") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedKind != null) selectedKind = null else navController.popBackStack()
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        val kind = selectedKind
        if (kind == null) {
            PushRuleKindPicker(
                ruleset = ruleset,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onSelect = { selectedKind = it }
            )
        } else {
            PushRuleKindList(
                kind = kind,
                rules = ruleset.rules(kind),
                appViewModel = appViewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onAdd = { addKind = kind }
            )
        }
    }

    addKind?.let { kind ->
        AddPushRuleDialog(
            kind = kind,
            onDismiss = { addKind = null },
            onConfirm = { ruleId, pattern, conditions, preset ->
                appViewModel.putPushRule(
                    kind = kind,
                    ruleId = ruleId,
                    actions = actionsForPreset(preset),
                    conditions = conditions,
                    pattern = pattern
                )
                addKind = null
            }
        )
    }
}

@Composable
private fun PushRuleKindPicker(
    ruleset: net.vrkknn.andromuks.utils.PushRuleset,
    modifier: Modifier = Modifier,
    onSelect: (PushRuleKind) -> Unit
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Rules are evaluated by kind: override wins first, then content, room, sender and " +
                "finally underride. Default rules can be toggled and re-actioned but not deleted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (kind in PushRuleKind.ordered) {
            val count = ruleset.rules(kind).size
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(kind) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = kind.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (count == 1) "1 rule" else "$count rules",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            }
        }
    }
}

@Composable
private fun PushRuleKindList(
    kind: PushRuleKind,
    rules: List<PushRule>,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit
) {
    var query by remember(kind) { mutableStateOf("") }
    // memberUpdateCounter so resolved room/user names participate in filtering as profiles load.
    val counter = appViewModel.memberUpdateCounter
    val filtered = remember(rules, query, counter) {
        if (query.isBlank()) rules else rules.filter { it.matchesQuery(query, appViewModel) }
    }
    val targeted = filtered.filter { it.isTargeted }
    val general = filtered.filterNot { it.isTargeted }
    val grouped = targeted.isNotEmpty() && general.isNotEmpty()

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search ${kind.displayName.lowercase()} rules") },
            leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = if (query.isBlank()) "No ${kind.displayName.lowercase()} rules."
                        else "No rules match \"$query\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (grouped) {
                item { GroupHeader("Specific rooms & users") }
                items(targeted, key = { "${it.kind.apiName}:${it.ruleId}" }) { rule ->
                    PushRuleCard(rule = rule, appViewModel = appViewModel)
                }
                item { GroupHeader("General rules") }
                items(general, key = { "${it.kind.apiName}:${it.ruleId}" }) { rule ->
                    PushRuleCard(rule = rule, appViewModel = appViewModel)
                }
            } else {
                items(filtered, key = { "${it.kind.apiName}:${it.ruleId}" }) { rule ->
                    PushRuleCard(rule = rule, appViewModel = appViewModel)
                }
            }
            item {
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add ${kind.displayName.lowercase()} rule")
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** Filter predicate matching id, pattern, summary and resolved room/user display name. */
private fun PushRule.matchesQuery(query: String, appViewModel: AppViewModel): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (ruleId.lowercase().contains(q)) return true
    if (pattern?.lowercase()?.contains(q) == true) return true
    if (displayTitle().lowercase().contains(q)) return true
    if (humanSummary().lowercase().contains(q)) return true
    targetRoomId()?.let { rid ->
        if (appViewModel.getRoomById(rid)?.name?.lowercase()?.contains(q) == true) return true
    }
    targetUserId()?.let { uid ->
        if (appViewModel.getUserProfile(uid)?.displayName?.lowercase()?.contains(q) == true) return true
    }
    return false
}

@Composable
private fun PushRuleCard(
    rule: PushRule,
    appViewModel: AppViewModel
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showRawEditor by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RuleIdentity(
                    rule = rule,
                    appViewModel = appViewModel,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { appViewModel.setPushRuleEnabled(rule.kind, rule.ruleId, it) }
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit raw JSON") },
                            onClick = { menuOpen = false; showRawEditor = true }
                        )
                        if (!rule.isDefault) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { menuOpen = false; showDeleteConfirm = true }
                            )
                        }
                    }
                }
            }

            PushRulePresetChips(
                rule = rule,
                onSelect = { preset -> appViewModel.setPushRulePreset(rule.kind, rule.ruleId, preset) }
            )
        }
    }

    if (showRawEditor) {
        RawRuleEditorDialog(
            rule = rule,
            onDismiss = { showRawEditor = false },
            onSave = { actions, conditions, pattern ->
                if (rule.isDefault) {
                    // Default rules can only have their actions replaced.
                    appViewModel.setPushRuleActions(rule.kind, rule.ruleId, actions)
                } else {
                    appViewModel.putPushRule(rule.kind, rule.ruleId, actions, conditions, pattern)
                }
                showRawEditor = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete rule") },
            text = { Text("Delete the rule \"${rule.displayTitle()}\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        appViewModel.deletePushRule(rule.kind, rule.ruleId)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * The leading identity block of a rule card: a room avatar+name (room-targeted rules), a user
 * avatar+name (sender rules), or a title+summary (general rules). Public so the per-room dialog in
 * RoomInfo can render the same identity for rules affecting a room.
 */
@Composable
fun RuleIdentity(
    rule: PushRule,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val roomId = rule.targetRoomId()
    val userId = rule.targetUserId()
    when {
        roomId != null -> {
            val room = appViewModel.getRoomById(roomId)
            val name = room?.name?.takeIf { it.isNotBlank() } ?: roomId
            IdentityRow(
                modifier = modifier,
                mxcUrl = room?.avatarUrl,
                appViewModel = appViewModel,
                fallbackText = name,
                avatarId = roomId,
                title = name,
                subtitle = roomId
            )
        }
        userId != null -> {
            // Refresh as profiles arrive; fetch on demand if we don't have one yet.
            val counter = appViewModel.memberUpdateCounter
            val profile = remember(userId, counter) { appViewModel.getUserProfile(userId) }
            LaunchedEffect(userId) {
                if (appViewModel.getUserProfile(userId) == null) {
                    appViewModel.requestBasicUserProfile(userId) {}
                }
            }
            val name = profile?.displayName?.takeIf { it.isNotBlank() } ?: userId
            IdentityRow(
                modifier = modifier,
                mxcUrl = profile?.avatarUrl,
                appViewModel = appViewModel,
                fallbackText = name,
                avatarId = userId,
                title = name,
                subtitle = userId
            )
        }
        else -> {
            Column(modifier = modifier) {
                Text(
                    text = rule.displayTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = rule.humanSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun IdentityRow(
    modifier: Modifier,
    mxcUrl: String?,
    appViewModel: AppViewModel,
    fallbackText: String,
    avatarId: String,
    title: String,
    subtitle: String
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AvatarImage(
            mxcUrl = mxcUrl,
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            fallbackText = fallbackText,
            size = 40.dp,
            userId = avatarId,
            displayName = title
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Filter chips for the four selectable presets, with a "Custom" indicator when nothing matches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushRulePresetChips(
    rule: PushRule,
    onSelect: (NotificationPreset) -> Unit
) {
    val current = rule.notificationPreset()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRowChips {
            NotificationPreset.selectable.forEach { preset ->
                FilterChip(
                    selected = current == preset,
                    onClick = { onSelect(preset) },
                    label = { Text(preset.label) }
                )
            }
        }
        if (current == NotificationPreset.CUSTOM) {
            Text(
                text = "Custom actions — use \"Edit raw JSON\" to change without losing detail.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Simple wrapping row for chips (avoids depending on Accompanist FlowRow signatures). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun RawRuleEditorDialog(
    rule: PushRule,
    onDismiss: () -> Unit,
    onSave: (actions: List<Any>, conditions: List<Map<String, Any>>, pattern: String) -> Unit
) {
    val initialJson = remember(rule) { ruleContentToJson(rule).toString(2) }
    var text by remember { mutableStateOf(initialJson) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit raw JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (rule.isDefault)
                        "Default rule — only the \"actions\" array is applied."
                    else
                        "Edit actions, conditions and pattern.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 360.dp)
                )
                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val obj = JSONObject(text)
                    val actions = jsonArrayToList(obj.optJSONArray("actions") ?: JSONArray())
                    val conditions = jsonArrayToConditions(obj.optJSONArray("conditions") ?: JSONArray())
                    val pattern = obj.optString("pattern")
                    onSave(actions, conditions, pattern)
                } catch (e: Exception) {
                    error = "Invalid JSON: ${e.message}"
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AddPushRuleDialog(
    kind: PushRuleKind,
    onDismiss: () -> Unit,
    onConfirm: (ruleId: String, pattern: String, conditions: List<Map<String, Any>>, preset: NotificationPreset) -> Unit
) {
    var ruleId by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var conditionsText by remember { mutableStateOf("[]") }
    var preset by remember { mutableStateOf(NotificationPreset.NOTIFY) }
    var error by remember { mutableStateOf<String?>(null) }

    val idLabel = when (kind) {
        PushRuleKind.SENDER -> "Sender (user ID, e.g. @bot:server)"
        PushRuleKind.ROOM -> "Room ID (e.g. !abc:server)"
        PushRuleKind.CONTENT -> "Rule ID (a name for this keyword rule)"
        else -> "Rule ID (a name for this rule)"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${kind.displayName.lowercase()} rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ruleId,
                    onValueChange = { ruleId = it; error = null },
                    label = { Text(idLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (kind == PushRuleKind.CONTENT) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it; error = null },
                        label = { Text("Pattern (glob, e.g. *keyword*)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (kind == PushRuleKind.OVERRIDE || kind == PushRuleKind.UNDERRIDE) {
                    OutlinedTextField(
                        value = conditionsText,
                        onValueChange = { conditionsText = it; error = null },
                        label = { Text("Conditions (JSON array)") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 200.dp)
                    )
                }
                Text("Action", style = MaterialTheme.typography.labelMedium)
                FlowRowChips {
                    NotificationPreset.selectable.forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = { preset = p },
                            label = { Text(p.label) }
                        )
                    }
                }
                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = ruleId.trim()
                if (id.isEmpty()) { error = "Rule ID is required"; return@TextButton }
                if (kind == PushRuleKind.CONTENT && pattern.isBlank()) { error = "Pattern is required"; return@TextButton }
                val conditions = if (kind == PushRuleKind.OVERRIDE || kind == PushRuleKind.UNDERRIDE) {
                    try {
                        jsonArrayToConditions(JSONArray(conditionsText))
                    } catch (e: Exception) {
                        error = "Invalid conditions JSON: ${e.message}"; return@TextButton
                    }
                } else emptyList()
                onConfirm(id, pattern.trim(), conditions, preset)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── JSON helpers ──────────────────────────────────────────────────────────

private fun ruleContentToJson(rule: PushRule): JSONObject {
    val obj = JSONObject()
    val actions = JSONArray()
    rule.actions.forEach { actions.put(actionToJson(it)) }
    obj.put("actions", actions)
    if (!rule.isDefault) {
        val conditions = JSONArray()
        rule.conditions.forEach { conditions.put(JSONObject(it.toWire())) }
        obj.put("conditions", conditions)
        obj.put("pattern", rule.pattern ?: "")
    }
    return obj
}

private fun actionToJson(action: PushAction): Any = when (val wire = action.toWire()) {
    is Map<*, *> -> JSONObject(wire.entries.associate { it.key.toString() to it.value })
    else -> wire
}

private fun jsonArrayToList(arr: JSONArray): List<Any> {
    val out = ArrayList<Any>(arr.length())
    for (i in 0 until arr.length()) {
        when (val v = arr.opt(i)) {
            is JSONObject -> out.add(jsonObjectToMap(v))
            null -> {}
            else -> out.add(v)
        }
    }
    return out
}

private fun jsonArrayToConditions(arr: JSONArray): List<Map<String, Any>> {
    val out = ArrayList<Map<String, Any>>(arr.length())
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { out.add(jsonObjectToMap(it)) }
    }
    return out
}

private fun jsonObjectToMap(obj: JSONObject): Map<String, Any> {
    val map = LinkedHashMap<String, Any>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        obj.opt(key)?.let { map[key] = it }
    }
    return map
}
