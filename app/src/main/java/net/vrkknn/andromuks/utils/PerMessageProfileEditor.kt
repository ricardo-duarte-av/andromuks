package net.vrkknn.andromuks.utils

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.AccountDataCache
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.ui.components.AvatarImage
import org.json.JSONArray
import org.json.JSONObject

/**
 * One stored per-message profile (MSC4461 revision 2).
 *
 * [id], [displayname] and [avatarUrl] come from MSC4144 and are copied verbatim into the outgoing
 * `com.beeper.per_message_profile`. [prefixes] is the private `trigger.prefix` list and MUST NOT be
 * sent — the backend matches it against the composer text, strips it, and attaches the profile.
 *
 * Prefixes are case-sensitive and matched verbatim, trailing space included: `cat:` does not match
 * `cat: meow`, only `cat: ` does. Order matters both inside [prefixes] and across the stored list —
 * all prefixes of the first profile take priority over the second profile's.
 */
data class PerMessageProfileEntry(val id: String, val displayname: String, val avatarUrl: String, val prefixes: List<String>) {
    /** The profile as it is sent inside `com.beeper.per_message_profile` — never includes `trigger`. */
    fun toContentMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "id" to id,
            "displayname" to displayname,
        )
        if (avatarUrl.isNotBlank()) map["avatar_url"] = avatarUrl
        return map
    }
}

/** MSC4461 rev-2 unstable key — the only one gomuks reads (`event.AccountDataPerMessageProfiles`). */
private const val V2_UNSTABLE_KEY = "fi.mau.msc4461.per_message_profiles.v2"

/** Stable key. Same name in rev-1 and rev-2, but the content shape differs — always check for `profiles`. */
private const val STABLE_KEY = "m.per_message_profiles"

/** Rev-1 unstable key, holding the old shortcode→profile map. Read for migration, then blanked. */
private const val LEGACY_V1_KEY = "fi.mau.msc4461.per_message_profiles"

/**
 * Read the stored profiles, preferring the rev-2 array. Falls back to converting rev-1 data so the
 * picker keeps working before [migrateLegacyProfilesIfNeeded] has run.
 */
fun readPerMessageProfiles(): List<PerMessageProfileEntry> {
    readV2Profiles()?.let { return it }
    return readLegacyProfiles()
}

/** Rev-2 profiles, or null when no key holds array-shaped content. */
private fun readV2Profiles(): List<PerMessageProfileEntry>? {
    val content = contentOf(V2_UNSTABLE_KEY)?.takeIf { it.has("profiles") }
        ?: contentOf(STABLE_KEY)?.takeIf { it.has("profiles") }
        ?: return null
    val array = content.optJSONArray("profiles") ?: return emptyList()
    val result = mutableListOf<PerMessageProfileEntry>()
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i)
        val id = entry?.optString("id").orEmpty()
        if (entry == null || id.isBlank()) continue
        val prefixArray = entry.optJSONObject("trigger")?.optJSONArray("prefix")
        val prefixes = mutableListOf<String>()
        if (prefixArray != null) {
            for (p in 0 until prefixArray.length()) {
                prefixArray.optString(p).takeIf { it.isNotEmpty() }?.let { prefixes.add(it) }
            }
        }
        result.add(
            PerMessageProfileEntry(
                id = id,
                displayname = entry.optString("displayname", id),
                avatarUrl = entry.optString("avatar_url", ""),
                prefixes = prefixes,
            ),
        )
    }
    return result
}

/**
 * Rev-1 profiles converted to the rev-2 model. The old format had no triggers, so each shortcode
 * becomes a `"<shortcode>: "` prefix — the colon convention gomuks used before commit 951bac5.
 */
private fun readLegacyProfiles(): List<PerMessageProfileEntry> {
    val content = contentOf(LEGACY_V1_KEY)?.takeIf { !it.has("profiles") }
        ?: contentOf(STABLE_KEY)?.takeIf { !it.has("profiles") }
        ?: return emptyList()
    val result = mutableListOf<PerMessageProfileEntry>()
    val keys = content.keys()
    while (keys.hasNext()) {
        val shortcode = keys.next()
        val entry = content.optJSONObject(shortcode) ?: continue
        result.add(
            PerMessageProfileEntry(
                id = entry.optString("id", shortcode),
                displayname = entry.optString("displayname", shortcode),
                avatarUrl = entry.optString("avatar_url", ""),
                prefixes = listOf("$shortcode: "),
            ),
        )
    }
    return result.sortedBy { it.id }
}

private fun contentOf(type: String): JSONObject? = AccountDataCache.getAccountData(type)?.optJSONObject("content")

/**
 * True when [draft] is a bare `/pmp` / `/profile` command with no shortcode after it — the composer
 * opens the profile picker on this, and clears the draft once a profile is picked.
 */
fun isBarePerMessageProfileCommand(draft: String): Boolean {
    val trimmed = draft.trim()
    return trimmed.equals("/pmp", ignoreCase = true) || trimmed.equals("/profile", ignoreCase = true)
}

/** `{"profiles": [...]}` — the account data content for [profiles], triggers included. */
private fun buildProfilesContentMap(profiles: List<PerMessageProfileEntry>): Map<String, Any> {
    val list = profiles.map { entry ->
        val map = entry.toContentMap().toMutableMap()
        if (entry.prefixes.isNotEmpty()) {
            map["trigger"] = mapOf("prefix" to entry.prefixes)
        }
        map
    }
    return mapOf("profiles" to list)
}

/**
 * Persist [profiles] to both the rev-2 unstable key and the stable key, and update
 * [AccountDataCache] optimistically so the picker reflects the change before the sync round-trip.
 */
private fun writePerMessageProfiles(appViewModel: AppViewModel, profiles: List<PerMessageProfileEntry>) {
    val contentMap = buildProfilesContentMap(profiles)
    appViewModel.setAccountDataContent(V2_UNSTABLE_KEY, contentMap)
    appViewModel.setAccountDataContent(STABLE_KEY, contentMap)

    val array = JSONArray()
    profiles.forEach { entry ->
        val entryJson = JSONObject()
            .put("id", entry.id)
            .put("displayname", entry.displayname)
        if (entry.avatarUrl.isNotBlank()) entryJson.put("avatar_url", entry.avatarUrl)
        if (entry.prefixes.isNotEmpty()) {
            entryJson.put("trigger", JSONObject().put("prefix", JSONArray(entry.prefixes)))
        }
        array.put(entryJson)
    }
    val wrapper = JSONObject().put("content", JSONObject().put("profiles", array))
    AccountDataCache.setAccountData(V2_UNSTABLE_KEY, wrapper)
    AccountDataCache.setAccountData(STABLE_KEY, wrapper)
}

/**
 * One-shot rev-1 → rev-2 migration: if nothing holds array-shaped content but legacy data exists,
 * rewrite it in the new shape and blank the old unstable key so it stops shadowing.
 * Returns the profiles to display.
 */
private fun migrateLegacyProfilesIfNeeded(appViewModel: AppViewModel): List<PerMessageProfileEntry> {
    readV2Profiles()?.let { return it }
    val legacy = readLegacyProfiles()
    if (legacy.isEmpty()) return emptyList()
    android.util.Log.i("Andromuks", "PerMessageProfiles: migrating ${legacy.size} profile(s) to MSC4461 rev-2")
    writePerMessageProfiles(appViewModel, legacy)
    appViewModel.setAccountDataContent(LEGACY_V1_KEY, emptyMap())
    AccountDataCache.setAccountData(LEGACY_V1_KEY, JSONObject().put("content", JSONObject()))
    return legacy
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerMessageProfileEditorScreen(navController: NavController, appViewModel: AppViewModel) {
    // The avatar-rendering composables below take these as parameters rather than reading the
    // view model, so hoist them once here.
    val homeserverUrl = appViewModel.homeserverUrl
    val authToken = appViewModel.authToken
    var profiles by remember { mutableStateOf(readPerMessageProfiles()) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var showDeleteConfirmFor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        profiles = migrateLegacyProfilesIfNeeded(appViewModel)
    }

    fun saveProfiles(updated: List<PerMessageProfileEntry>) {
        profiles = updated
        writePerMessageProfiles(appViewModel, updated)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per-Message Profiles") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingIndex = null
                showAddEditDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add profile")
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (profiles.isEmpty()) {
                Text(
                    text = "No per-message profiles yet.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                )
            } else {
                // Stored order is match priority (MSC4461: earlier profiles win), so never sort here.
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(profiles) { index, profile ->
                        ProfileListItem(
                            profile = profile,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onEdit = {
                                editingIndex = index
                                showAddEditDialog = true
                            },
                            onDelete = { showDeleteConfirmFor = index },
                        )
                    }
                }
            }
        }
    }

    if (showAddEditDialog) {
        AddEditProfileDialog(
            existing = editingIndex?.let { profiles.getOrNull(it) },
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            onDismiss = { showAddEditDialog = false },
            onSave = { updated ->
                val newProfiles = profiles.toMutableList()
                val index = editingIndex
                if (index != null && index in newProfiles.indices) {
                    newProfiles[index] = updated
                } else {
                    newProfiles.add(updated)
                }
                saveProfiles(newProfiles)
                showAddEditDialog = false
            },
        )
    }

    showDeleteConfirmFor?.let { index ->
        val profile = profiles.getOrNull(index)
        AlertDialog(
            onDismissRequest = { showDeleteConfirmFor = null },
            title = { Text("Delete profile") },
            text = { Text("Delete the \"${profile?.displayname ?: profile?.id}\" profile?") },
            confirmButton = {
                Button(
                    onClick = {
                        val newProfiles = profiles.toMutableList()
                        if (index in newProfiles.indices) newProfiles.removeAt(index)
                        saveProfiles(newProfiles)
                        showDeleteConfirmFor = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmFor = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProfileListItem(profile: PerMessageProfileEntry, homeserverUrl: String, authToken: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarImage(
            mxcUrl = profile.avatarUrl.takeIf { it.isNotBlank() },
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = profile.displayname,
            size = 44.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayname,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** `id — "prefix" "prefix"`, with prefixes quoted so trailing spaces stay visible. */
private fun PerMessageProfileEntry.subtitle(): String = if (prefixes.isEmpty()) {
    id
} else {
    "$id — ${prefixes.joinToString(" ") { "\"$it\"" }}"
}

@Composable
private fun AddEditProfileDialog(
    existing: PerMessageProfileEntry?,
    homeserverUrl: String,
    authToken: String,
    onDismiss: () -> Unit,
    onSave: (PerMessageProfileEntry) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var id by remember { mutableStateOf(existing?.id ?: "") }
    var displayname by remember { mutableStateOf(existing?.displayname ?: "") }
    var avatarUrl by remember { mutableStateOf(existing?.avatarUrl ?: "") }
    var prefixes by remember { mutableStateOf(existing?.prefixes?.ifEmpty { listOf("") } ?: listOf("")) }
    var avatarUploadInProgress by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val mimeType = context.contentResolver.getType(selectedUri)
            if (mimeType?.startsWith("image/") == true) {
                avatarUploadInProgress = true
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val result = MediaUploadUtils.uploadMedia(
                            context = context,
                            uri = selectedUri,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            isEncrypted = false,
                            compressOriginal = false,
                        )
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            if (result != null) {
                                avatarUrl = result.mxcUrl
                            } else {
                                error = "Failed to upload image"
                            }
                            avatarUploadInProgress = false
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            error = "Upload error: ${e.message}"
                            avatarUploadInProgress = false
                        }
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!avatarUploadInProgress) onDismiss() },
        title = { Text(if (existing != null) "Edit Profile" else "Add Profile") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { newId ->
                        val cleaned = newId.replace(" ", "").replace("\n", "")
                        // Keep the default prefix in step with the id until the user edits it.
                        if (prefixes.size == 1 && (prefixes[0].isEmpty() || prefixes[0] == "$id: ")) {
                            prefixes = listOf(if (cleaned.isEmpty()) "" else "$cleaned: ")
                        }
                        id = cleaned
                    },
                    label = { Text("ID (no spaces)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = displayname,
                    onValueChange = { displayname = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                PrefixEditor(
                    prefixes = prefixes,
                    onPrefixesChange = { prefixes = it },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val previewHttpUrl = remember(avatarUrl) {
                        AvatarUtils.getFullImageUrl(context, avatarUrl, homeserverUrl)
                    }
                    if (previewHttpUrl != null) {
                        val imageLoader = remember { ImageLoaderSingleton.get(context) }
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(previewHttpUrl).build(),
                            imageLoader = imageLoader,
                            contentDescription = "Avatar preview",
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        AvatarImage(
                            mxcUrl = null,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            fallbackText = displayname.ifBlank { id },
                            size = 44.dp,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (avatarUploadInProgress) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Uploading…", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Button(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Choose Avatar", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (avatarUrl.isNotBlank()) {
                            TextButton(
                                onClick = { avatarUrl = "" },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text("Remove avatar", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedId = id.trim()
                    val trimmedDisplayname = displayname.trim()
                    if (trimmedId.isBlank()) {
                        error = "ID is required"
                        return@Button
                    }
                    if (trimmedId.contains(" ")) {
                        error = "ID must not contain spaces"
                        return@Button
                    }
                    if (trimmedDisplayname.isBlank()) {
                        error = "Display name is required"
                        return@Button
                    }
                    onSave(
                        PerMessageProfileEntry(
                            id = trimmedId,
                            displayname = trimmedDisplayname,
                            avatarUrl = avatarUrl,
                            // Prefixes are stored verbatim — trailing spaces are part of the match.
                            prefixes = prefixes.filter { it.isNotEmpty() },
                        ),
                    )
                },
                enabled = !avatarUploadInProgress,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { if (!avatarUploadInProgress) onDismiss() }) { Text("Cancel") }
        },
    )
}

/**
 * Editor for a profile's `trigger.prefix` list. Typing a prefix at the start of a message makes the
 * backend use this profile and strip the prefix. Values are never trimmed — `"cat: "` and `"cat:"`
 * are different triggers.
 */
@Composable
private fun PrefixEditor(prefixes: List<String>, onPrefixesChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Trigger prefixes",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "Typing one of these at the start of a message uses this profile. Case-sensitive; " +
                "include the trailing space if you want \"cat: meow\" to match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        prefixes.forEachIndexed { index, prefix ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { newValue ->
                        onPrefixesChange(prefixes.toMutableList().also { it[index] = newValue.replace("\n", "") })
                    },
                    label = { Text("Prefix ${index + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                IconButton(
                    onClick = { onPrefixesChange(prefixes.toMutableList().also { it.removeAt(index) }) },
                    enabled = prefixes.size > 1,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove prefix", modifier = Modifier.size(18.dp))
                }
            }
        }
        TextButton(
            onClick = { onPrefixesChange(prefixes + "") },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add prefix", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Composer chip showing the profile armed for the next send. Tapping it reopens the picker; the ✕
 * disarms it. Sits above the input, like the reply/edit previews.
 */
@Composable
fun PerMessageProfileChip(
    profile: PerMessageProfileEntry,
    homeserverUrl: String,
    authToken: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AvatarImage(
            mxcUrl = profile.avatarUrl.takeIf { it.isNotBlank() },
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = profile.displayname,
            size = 24.dp,
        )
        Text(
            text = "Sending as ${profile.displayname}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear per-message profile",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Floating per-message profile picker opened from the composer. Selecting a profile arms it for the
 * next send (the profile travels in `base_content`); it does not rewrite the draft.
 */
@Composable
fun PerMessageProfilePicker(homeserverUrl: String, authToken: String, onProfileSelected: (PerMessageProfileEntry) -> Unit, modifier: Modifier = Modifier) {
    val profiles = remember { readPerMessageProfiles() }

    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 4.dp,
    ) {
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No Per Message profile available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.height(220.dp),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(profiles) { _, profile ->
                    PerMessageProfilePickerItem(
                        profile = profile,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onSelected = { onProfileSelected(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PerMessageProfilePickerItem(profile: PerMessageProfileEntry, homeserverUrl: String, authToken: String, onSelected: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarImage(
            mxcUrl = profile.avatarUrl.takeIf { it.isNotBlank() },
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            fallbackText = profile.displayname,
            size = 36.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayname,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
