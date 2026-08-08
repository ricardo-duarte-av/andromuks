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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.vrkknn.andromuks.AccountDataCache
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.RoomAccountDataCache
import net.vrkknn.andromuks.ui.components.AvatarImage
import org.json.JSONArray
import org.json.JSONObject

/**
 * One trigger condition on a stored profile (MSC4461 revision 3).
 *
 * The composer text must start with [prefix] **and** end with [suffix] for the trigger to fire;
 * either may be empty, but a trigger with both empty never matches. Both ends are stripped from the
 * body before sending.
 *
 * Matching is case-sensitive and verbatim — surrounding spaces are part of the trigger, so `"cat:"`
 * does not match `cat: meow` while `"cat: "` does. gomuks also requires the two not to overlap
 * (`len(input) >= len(prefix) + len(suffix)`), so a text consisting of nothing but the trigger
 * strings will not match.
 */
data class PerMessageProfileTrigger(val prefix: String = "", val suffix: String = "") {
    fun isBlank(): Boolean = prefix.isEmpty() && suffix.isEmpty()

    /** Quoted so leading/trailing spaces stay visible in the UI. */
    fun label(): String = when {
        prefix.isNotEmpty() && suffix.isNotEmpty() -> "\"$prefix\"…\"$suffix\""
        suffix.isNotEmpty() -> "…\"$suffix\""
        else -> "\"$prefix\""
    }
}

/**
 * One stored per-message profile (MSC4461 revision 3).
 *
 * [id], [displayname], [avatarUrl] and [extras] come from MSC4144 and are copied verbatim into the
 * outgoing `com.beeper.per_message_profile`. [triggers] is private and MUST NOT be sent — the
 * backend matches it against the composer text, strips the match, and attaches the profile.
 *
 * Order is match priority both inside [triggers] and across the stored list: all triggers of the
 * first profile take priority over the second profile's, and room-scoped profiles beat global ones.
 */
data class PerMessageProfileEntry(
    val id: String,
    val displayname: String,
    val avatarUrl: String,
    val triggers: List<PerMessageProfileTrigger>,
    val extras: Map<String, Any> = emptyMap(),
) {
    /** The profile as it is sent inside `com.beeper.per_message_profile` — never includes `triggers`. */
    fun toContentMap(): Map<String, Any> {
        // MSC4461 rev-3: every field except `triggers` is copied into the message, including ones
        // this client doesn't model. Known fields are written last so they always win.
        val map = extras.toMutableMap()
        map["id"] = id
        map["displayname"] = displayname
        if (avatarUrl.isNotBlank()) map["avatar_url"] = avatarUrl else map.remove("avatar_url")
        return map
    }
}

/**
 * The profiles and default stored in one scope (global account data, or one room's account data).
 *
 * [defaultProfileId] distinguishes three states, and the difference is load-bearing per MSC4461:
 * `null` means the field is absent or JSON null (fall through to the global value), `""` means
 * "explicitly no profile here" (do not fall through), and any other value names a profile.
 */
data class PerMessageProfileStore(val profiles: List<PerMessageProfileEntry> = emptyList(), val defaultProfileId: String? = null) {
    fun isEmpty(): Boolean = profiles.isEmpty() && defaultProfileId == null
}

/** MSC4461 rev-3 unstable key — the only one gomuks reads (`event.AccountDataPerMessageProfiles`). */
private const val V3_UNSTABLE_KEY = "fi.mau.msc4461.per_message_profiles.v3"

/** Stable key. Same name in every revision but with incompatible shapes, so the reader sniffs. */
private const val STABLE_KEY = "m.per_message_profiles"

/** Rev-2 unstable key, holding `trigger: {prefix: [...]}` entries. Read for migration, then blanked. */
private const val V2_UNSTABLE_KEY = "fi.mau.msc4461.per_message_profiles.v2"

/** Rev-1 unstable key, holding the old shortcode→profile map. Read for migration, then blanked. */
private const val LEGACY_V1_KEY = "fi.mau.msc4461.per_message_profiles"

/**
 * Global profiles, preferring the rev-3 key and falling back through rev-2 and rev-1 so the picker
 * keeps working before [migrateLegacyProfilesIfNeeded] has run.
 */
fun readGlobalPerMessageProfiles(): PerMessageProfileStore {
    parseStore(contentOf(V3_UNSTABLE_KEY))?.let { return it }
    parseStore(contentOf(STABLE_KEY))?.let { return it }
    parseStore(contentOf(V2_UNSTABLE_KEY))?.let { return it }
    return readLegacyProfiles()
}

/**
 * One room's profiles. Rev-3 introduced room-scoped storage, so there is no legacy shape to fall
 * back to here. Returns an empty store when the room has none.
 */
fun readRoomPerMessageProfiles(roomId: String): PerMessageProfileStore = parseStore(roomContentOf(roomId, V3_UNSTABLE_KEY))
    ?: parseStore(roomContentOf(roomId, STABLE_KEY))
    ?: PerMessageProfileStore()

/**
 * Every profile that can be used in [roomId], in gomuks' match order: room-scoped profiles first,
 * then global ones. Pass null for the global list alone.
 */
fun readPerMessageProfiles(roomId: String? = null): List<PerMessageProfileEntry> =
    (roomId?.let { readRoomPerMessageProfiles(it).profiles } ?: emptyList()) + readGlobalPerMessageProfiles().profiles

/**
 * The profile gomuks would apply when no trigger matches, mirroring `PickPerMessageProfile`: the
 * room's `default_profile_id` wins when present (an empty string meaning "none here" and suppressing
 * the global value), otherwise the global one. Null when there is no default or it names a profile
 * that no longer exists.
 */
fun resolveDefaultPerMessageProfile(roomId: String?): PerMessageProfileEntry? {
    val room = roomId?.let { readRoomPerMessageProfiles(it) } ?: PerMessageProfileStore()
    val global = readGlobalPerMessageProfiles()
    val id = room.defaultProfileId ?: global.defaultProfileId
    if (id.isNullOrEmpty()) return null
    return (room.profiles + global.profiles).firstOrNull { it.id == id }
}

/**
 * Parse rev-2/rev-3 array-shaped content, or null when [content] is absent or is not array-shaped
 * (i.e. it is rev-1's shortcode map, which [readLegacyProfiles] handles).
 */
internal fun parseStore(content: JSONObject?): PerMessageProfileStore? {
    if (content == null || !content.has("profiles")) return null
    val array = content.optJSONArray("profiles")
    val result = mutableListOf<PerMessageProfileEntry>()
    for (i in 0 until (array?.length() ?: 0)) {
        parseEntry(array?.optJSONObject(i))?.let { result.add(it) }
    }
    return PerMessageProfileStore(profiles = result, defaultProfileId = readNullableString(content, "default_profile_id"))
}

/**
 * `null` for both an absent key and an explicit JSON null; the raw string otherwise. `optString`
 * cannot tell those apart, and for `default_profile_id` the distinction between null and `""` is
 * what decides whether the global default still applies.
 */
private fun readNullableString(content: JSONObject, key: String): String? = if (content.has(key) && !content.isNull(key)) content.optString(key) else null

/** One profile object, tolerating both the rev-3 `triggers` array and the rev-2 `trigger.prefix` list. */
internal fun parseEntry(entry: JSONObject?): PerMessageProfileEntry? {
    val id = entry?.optString("id").orEmpty()
    if (entry == null || id.isBlank()) return null
    val triggers = mutableListOf<PerMessageProfileTrigger>()
    val triggerArray = entry.optJSONArray("triggers")
    if (triggerArray != null) {
        for (t in 0 until triggerArray.length()) {
            val trigger = triggerArray.optJSONObject(t) ?: continue
            PerMessageProfileTrigger(trigger.optString("prefix"), trigger.optString("suffix"))
                .takeIf { !it.isBlank() }
                ?.let { triggers.add(it) }
        }
    } else {
        // Rev-2: a single `trigger` object holding a prefix array.
        val prefixArray = entry.optJSONObject("trigger")?.optJSONArray("prefix")
        for (p in 0 until (prefixArray?.length() ?: 0)) {
            prefixArray?.optString(p)?.takeIf { it.isNotEmpty() }?.let { triggers.add(PerMessageProfileTrigger(prefix = it)) }
        }
    }
    val known = setOf("id", "displayname", "avatar_url", "triggers", "trigger")
    val extras = mutableMapOf<String, Any>()
    entry.keys().forEach { key ->
        if (key !in known && !entry.isNull(key)) extras[key] = entry.get(key)
    }
    return PerMessageProfileEntry(
        id = id,
        displayname = entry.optString("displayname", id),
        avatarUrl = entry.optString("avatar_url", ""),
        triggers = triggers,
        extras = extras,
    )
}

/**
 * Rev-1 profiles converted to the current model. The old format had no triggers, so each shortcode
 * becomes a `"<shortcode>: "` prefix — the colon convention gomuks used before commit 951bac5.
 */
private fun readLegacyProfiles(): PerMessageProfileStore {
    val content = contentOf(LEGACY_V1_KEY)?.takeIf { !it.has("profiles") }
        ?: contentOf(STABLE_KEY)?.takeIf { !it.has("profiles") }
        ?: return PerMessageProfileStore()
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
                triggers = listOf(PerMessageProfileTrigger(prefix = "$shortcode: ")),
            ),
        )
    }
    return PerMessageProfileStore(profiles = result.sortedBy { it.id })
}

private fun contentOf(type: String): JSONObject? = AccountDataCache.getAccountData(type)?.optJSONObject("content")

/**
 * Room account data is cached either wrapped in a `content` object or as the bare content, depending
 * on the sync shape — the same ambiguity [net.vrkknn.andromuks.RoomAccountDataCache] documents for
 * `m.fully_read`. Prefer the wrapper, fall back to the object itself.
 */
private fun roomContentOf(roomId: String, type: String): JSONObject? {
    val data = RoomAccountDataCache.getRoomAccountData(roomId, type) ?: return null
    return data.optJSONObject("content") ?: data
}

/**
 * True when [draft] is a bare `/pmp` / `/profile` command with no shortcode after it — the composer
 * opens the profile picker on this, and clears the draft once a profile is picked.
 */
fun isBarePerMessageProfileCommand(draft: String): Boolean {
    val trimmed = draft.trim()
    return trimmed.equals("/pmp", ignoreCase = true) || trimmed.equals("/profile", ignoreCase = true)
}

/** `{"profiles": [...], "default_profile_id": …}` — the account data content for [store]. */
private fun buildStoreContent(store: PerMessageProfileStore): JSONObject {
    val array = JSONArray()
    store.profiles.forEach { entry ->
        val entryJson = JSONObject(entry.toContentMap())
        val triggers = entry.triggers.filter { !it.isBlank() }
        if (triggers.isNotEmpty()) {
            val triggerArray = JSONArray()
            triggers.forEach { trigger ->
                val triggerJson = JSONObject()
                if (trigger.prefix.isNotEmpty()) triggerJson.put("prefix", trigger.prefix)
                if (trigger.suffix.isNotEmpty()) triggerJson.put("suffix", trigger.suffix)
                triggerArray.put(triggerJson)
            }
            entryJson.put("triggers", triggerArray)
        }
        array.put(entryJson)
    }
    val content = JSONObject().put("profiles", array)
    // Absent when null; written verbatim otherwise, so "" survives as the "no profile here" marker.
    store.defaultProfileId?.let { content.put("default_profile_id", it) }
    return content
}

/**
 * Persist [store] to both the rev-3 unstable key and the stable key. [roomId] scopes the write to a
 * room's account data; null writes global account data. `setAccountDataRaw` updates the matching
 * cache optimistically, so the picker reflects the change before the sync round-trip.
 */
private fun writePerMessageProfiles(appViewModel: AppViewModel, store: PerMessageProfileStore, roomId: String? = null) {
    appViewModel.setAccountDataRaw(V3_UNSTABLE_KEY, buildStoreContent(store), roomId)
    appViewModel.setAccountDataRaw(STABLE_KEY, buildStoreContent(store), roomId)
}

/**
 * One-shot rev-1/rev-2 → rev-3 migration for global profiles: if the rev-3 key holds nothing but an
 * older revision does, rewrite it in the new shape and blank the superseded unstable keys so they
 * stop shadowing. Returns the store to display.
 *
 * Note this makes the profiles invisible to a gomuks older than commit `c416f431`, which reads only
 * the rev-2 key. Dual-writing is not an option: rev-2 has no way to express a suffix trigger.
 */
private fun migrateLegacyProfilesIfNeeded(appViewModel: AppViewModel): PerMessageProfileStore {
    parseStore(contentOf(V3_UNSTABLE_KEY))?.let { return it }
    val legacy = readGlobalPerMessageProfiles()
    if (legacy.isEmpty()) return PerMessageProfileStore()
    android.util.Log.i("Andromuks", "PerMessageProfiles: migrating ${legacy.profiles.size} profile(s) to MSC4461 rev-3")
    writePerMessageProfiles(appViewModel, legacy)
    listOf(V2_UNSTABLE_KEY, LEGACY_V1_KEY).forEach { key ->
        if (contentOf(key)?.length()?.let { it > 0 } == true) {
            appViewModel.setAccountDataRaw(key, JSONObject())
        }
    }
    return legacy
}

/**
 * Editor for the profiles stored in one scope. [roomId] null edits global account data; non-null
 * edits that room's account data, where profiles and the default both outrank the global ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerMessageProfileEditorScreen(navController: NavController, appViewModel: AppViewModel, roomId: String? = null) {
    // The avatar-rendering composables below take these as parameters rather than reading the
    // view model, so hoist them once here.
    val homeserverUrl = appViewModel.homeserverUrl
    val authToken = appViewModel.authToken
    var store by remember {
        mutableStateOf(if (roomId != null) readRoomPerMessageProfiles(roomId) else readGlobalPerMessageProfiles())
    }
    // Only the global scope can name a profile that isn't in this scope's list, so the default
    // picker needs to offer global profiles too when editing a room.
    val globalProfiles = remember(store) { if (roomId != null) readGlobalPerMessageProfiles().profiles else emptyList() }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var showDeleteConfirmFor by remember { mutableStateOf<Int?>(null) }
    var showDefaultPicker by remember { mutableStateOf(false) }

    LaunchedEffect(roomId) {
        if (roomId == null) store = migrateLegacyProfilesIfNeeded(appViewModel)
    }

    fun save(updated: PerMessageProfileStore) {
        store = updated
        writePerMessageProfiles(appViewModel, updated, roomId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (roomId != null) "Room Per-Message Profiles" else "Per-Message Profiles") },
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
        // Stored order is match priority (MSC4461: earlier profiles win), so never sort here.
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            item {
                DefaultProfileCard(
                    store = store,
                    globalProfiles = globalProfiles,
                    isRoomScope = roomId != null,
                    onClick = { showDefaultPicker = true },
                )
            }
            if (store.profiles.isEmpty()) {
                item {
                    Text(
                        text = if (roomId != null) {
                            "No profiles defined just for this room.\nTap + to add one, or pick a global profile as this room's default above."
                        } else {
                            "No per-message profiles yet.\nTap + to add one."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    )
                }
            } else {
                itemsIndexed(store.profiles) { index, profile ->
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

    if (showAddEditDialog) {
        AddEditProfileDialog(
            existing = editingIndex?.let { store.profiles.getOrNull(it) },
            homeserverUrl = homeserverUrl,
            authToken = authToken,
            onDismiss = { showAddEditDialog = false },
            onSave = { updated ->
                val newProfiles = store.profiles.toMutableList()
                val index = editingIndex
                if (index != null && index in newProfiles.indices) {
                    newProfiles[index] = updated
                } else {
                    newProfiles.add(updated)
                }
                save(store.copy(profiles = newProfiles))
                showAddEditDialog = false
            },
        )
    }

    if (showDefaultPicker) {
        DefaultProfilePickerDialog(
            store = store,
            globalProfiles = globalProfiles,
            isRoomScope = roomId != null,
            onDismiss = { showDefaultPicker = false },
            onSelect = { newDefault ->
                save(store.copy(defaultProfileId = newDefault))
                showDefaultPicker = false
            },
        )
    }

    showDeleteConfirmFor?.let { index ->
        val profile = store.profiles.getOrNull(index)
        AlertDialog(
            onDismissRequest = { showDeleteConfirmFor = null },
            title = { Text("Delete profile") },
            text = { Text("Delete the \"${profile?.displayname ?: profile?.id}\" profile?") },
            confirmButton = {
                Button(
                    onClick = {
                        val newProfiles = store.profiles.toMutableList()
                        if (index in newProfiles.indices) newProfiles.removeAt(index)
                        // A default pointing at the profile we just removed would silently do nothing.
                        val newDefault = store.defaultProfileId?.takeIf { it.isEmpty() || newProfiles.any { p -> p.id == it } }
                        save(PerMessageProfileStore(newProfiles, newDefault))
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

/** Summary row for `default_profile_id` — the profile used when no trigger matches. */
@Composable
private fun DefaultProfileCard(store: PerMessageProfileStore, globalProfiles: List<PerMessageProfileEntry>, isRoomScope: Boolean, onClick: () -> Unit) {
    val summary = when (val id = store.defaultProfileId) {
        null -> if (isRoomScope) "Use the global default" else "None — send as yourself"
        "" -> "None — send as yourself, ignoring the global default"
        else -> (store.profiles + globalProfiles).firstOrNull { it.id == id }?.displayname ?: "$id (missing)"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = "Default profile", style = MaterialTheme.typography.labelMedium)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (isRoomScope) {
                "Used for messages in this room when no trigger matches. Overrides the global default."
            } else {
                "Used when no trigger matches and you haven't picked a profile."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Options for `default_profile_id`. The room scope has one extra option, because MSC4461 gives
 * "unset" (fall through to the global default) and `""` (explicitly no profile here) different
 * meanings — the empty string exists precisely so a room can opt out of a global default.
 */
@Composable
private fun DefaultProfilePickerDialog(
    store: PerMessageProfileStore,
    globalProfiles: List<PerMessageProfileEntry>,
    isRoomScope: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default profile") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DefaultProfileOption(
                    label = if (isRoomScope) "Use the global default" else "None — send as yourself",
                    selected = store.defaultProfileId == null,
                    onClick = { onSelect(null) },
                )
                if (isRoomScope) {
                    DefaultProfileOption(
                        label = "None in this room",
                        selected = store.defaultProfileId == "",
                        onClick = { onSelect("") },
                    )
                }
                store.profiles.forEach { profile ->
                    DefaultProfileOption(
                        label = profile.displayname,
                        selected = store.defaultProfileId == profile.id,
                        onClick = { onSelect(profile.id) },
                    )
                }
                // Global profiles are legal values for a room default; gomuks looks the id up in
                // room storage first and then falls back to global.
                if (globalProfiles.isNotEmpty()) {
                    Text(
                        text = "Global profiles",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    globalProfiles.forEach { profile ->
                        DefaultProfileOption(
                            label = profile.displayname,
                            selected = store.defaultProfileId == profile.id,
                            onClick = { onSelect(profile.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DefaultProfileOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** `id — "prefix" …"suffix"`, with triggers quoted so surrounding spaces stay visible. */
private fun PerMessageProfileEntry.subtitle(): String = if (triggers.isEmpty()) {
    id
} else {
    "$id — ${triggers.joinToString(" ") { it.label() }}"
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
    var triggers by remember {
        mutableStateOf(existing?.triggers?.ifEmpty { listOf(PerMessageProfileTrigger()) } ?: listOf(PerMessageProfileTrigger()))
    }
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
                        val only = triggers.singleOrNull()
                        if (only != null && only.suffix.isEmpty() && (only.prefix.isEmpty() || only.prefix == "$id: ")) {
                            triggers = listOf(PerMessageProfileTrigger(prefix = if (cleaned.isEmpty()) "" else "$cleaned: "))
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
                TriggerEditor(
                    triggers = triggers,
                    onTriggersChange = { triggers = it },
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
                            // Triggers are stored verbatim — surrounding spaces are part of the match.
                            triggers = triggers.filter { !it.isBlank() },
                            // Preserve MSC4144 fields we don't model rather than dropping them on edit.
                            extras = existing?.extras.orEmpty(),
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
 * Editor for a profile's `triggers` list. A trigger fires when the message starts with its prefix
 * *and* ends with its suffix; leaving one side empty matches on the other alone. Values are never
 * trimmed — `"cat: "` and `"cat:"` are different triggers.
 */
@Composable
private fun TriggerEditor(triggers: List<PerMessageProfileTrigger>, onTriggersChange: (List<PerMessageProfileTrigger>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Triggers",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "A message that starts with the prefix and ends with the suffix uses this profile, " +
                "with both stripped off. Leave one side blank to match on the other alone. " +
                "Case-sensitive; include the trailing space if you want \"cat: meow\" to match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        triggers.forEachIndexed { index, trigger ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = trigger.prefix,
                    onValueChange = { newValue ->
                        onTriggersChange(
                            triggers.toMutableList().also { it[index] = trigger.copy(prefix = newValue.replace("\n", "")) },
                        )
                    },
                    label = { Text("Prefix ${index + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = trigger.suffix,
                    onValueChange = { newValue ->
                        onTriggersChange(
                            triggers.toMutableList().also { it[index] = trigger.copy(suffix = newValue.replace("\n", "")) },
                        )
                    },
                    label = { Text("Suffix ${index + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                IconButton(
                    onClick = { onTriggersChange(triggers.toMutableList().also { it.removeAt(index) }) },
                    enabled = triggers.size > 1,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove trigger", modifier = Modifier.size(18.dp))
                }
            }
        }
        TextButton(
            onClick = { onTriggersChange(triggers + PerMessageProfileTrigger()) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add trigger", style = MaterialTheme.typography.labelMedium)
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
 * Passive chip for a `default_profile_id` that applies in this room. Unlike [PerMessageProfileChip]
 * there is nothing armed to clear — the backend applies this default itself whenever no trigger
 * matches, so the chip only tells the user what will happen and offers the picker to override it.
 *
 * Deliberately *not* wired into `base_content`: that would beat gomuks' own trigger matching, so
 * typing a prefix for another profile would silently send under the default instead.
 */
@Composable
fun PerMessageProfileDefaultChip(
    profile: PerMessageProfileEntry,
    homeserverUrl: String,
    authToken: String,
    onClick: () -> Unit,
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
            text = "Sending as ${profile.displayname} by default",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Floating per-message profile picker opened from the composer. Selecting a profile arms it for the
 * next send (the profile travels in `base_content`); it does not rewrite the draft.
 *
 * [roomId] brings in that room's own profiles, listed first because gomuks matches room storage
 * before global storage.
 */
@Composable
fun PerMessageProfilePicker(
    homeserverUrl: String,
    authToken: String,
    onProfileSelected: (PerMessageProfileEntry) -> Unit,
    modifier: Modifier = Modifier,
    roomId: String? = null,
) {
    val roomProfiles = remember(roomId) { roomId?.let { readRoomPerMessageProfiles(it).profiles }.orEmpty() }
    val globalProfiles = remember(roomId) { readGlobalPerMessageProfiles().profiles }

    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 4.dp,
    ) {
        if (roomProfiles.isEmpty() && globalProfiles.isEmpty()) {
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
                // Headers only when both scopes have something, so the common global-only case
                // looks exactly as it did before room storage existed.
                if (roomProfiles.isNotEmpty()) {
                    if (globalProfiles.isNotEmpty()) {
                        item { PerMessageProfileSectionHeader("This room") }
                    }
                    itemsIndexed(roomProfiles) { _, profile ->
                        PerMessageProfilePickerItem(
                            profile = profile,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onSelected = { onProfileSelected(profile) },
                        )
                    }
                    if (globalProfiles.isNotEmpty()) {
                        item { PerMessageProfileSectionHeader("All rooms") }
                    }
                }
                itemsIndexed(globalProfiles) { _, profile ->
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
private fun PerMessageProfileSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
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
