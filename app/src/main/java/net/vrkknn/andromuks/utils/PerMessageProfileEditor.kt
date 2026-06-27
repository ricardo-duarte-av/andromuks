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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

data class PerMessageProfileEntry(
    val shortcode: String,
    val id: String,
    val displayname: String,
    val avatarUrl: String
)

private val PRIMARY_KEY = "m.per_message_profiles"
private val SECONDARY_KEY = "fi.mau.msc4461.per_message_profiles"

fun readPerMessageProfiles(): Map<String, PerMessageProfileEntry> {
    val data = AccountDataCache.getAccountData(PRIMARY_KEY)
        ?: AccountDataCache.getAccountData(SECONDARY_KEY)
    val content = data?.optJSONObject("content") ?: return emptyMap()
    val result = mutableMapOf<String, PerMessageProfileEntry>()
    val keys = content.keys()
    while (keys.hasNext()) {
        val shortcode = keys.next()
        val entry = content.optJSONObject(shortcode) ?: continue
        result[shortcode] = PerMessageProfileEntry(
            shortcode = shortcode,
            id = entry.optString("id", shortcode),
            displayname = entry.optString("displayname", shortcode),
            avatarUrl = entry.optString("avatar_url", "")
        )
    }
    return result
}

private fun buildProfilesContentMap(profiles: Map<String, PerMessageProfileEntry>): Map<String, Any> {
    return profiles.mapValues { (_, entry) ->
        val m = mutableMapOf<String, Any>(
            "id" to entry.id,
            "displayname" to entry.displayname
        )
        if (entry.avatarUrl.isNotBlank()) m["avatar_url"] = entry.avatarUrl
        m
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerMessageProfileEditorScreen(
    navController: NavController,
    appViewModel: AppViewModel
) {
    var profiles by remember { mutableStateOf(readPerMessageProfiles()) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<PerMessageProfileEntry?>(null) }
    var showDeleteConfirmFor by remember { mutableStateOf<String?>(null) }

    fun saveProfiles(updated: Map<String, PerMessageProfileEntry>) {
        profiles = updated
        val contentMap = buildProfilesContentMap(updated)
        appViewModel.setAccountDataContent(PRIMARY_KEY, contentMap)
        appViewModel.setAccountDataContent(SECONDARY_KEY, contentMap)
        // Update local cache optimistically so the picker reflects changes immediately
        val contentJson = org.json.JSONObject()
        contentMap.forEach { (shortcode, entryAny) ->
            @Suppress("UNCHECKED_CAST")
            val entryMap = entryAny as Map<String, Any>
            val entryJson = org.json.JSONObject()
            entryMap.forEach { (k, v) -> entryJson.put(k, v) }
            contentJson.put(shortcode, entryJson)
        }
        val wrapper = org.json.JSONObject()
        wrapper.put("content", contentJson)
        AccountDataCache.setAccountData(PRIMARY_KEY, wrapper)
        AccountDataCache.setAccountData(SECONDARY_KEY, wrapper)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Per-Message Profiles") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingProfile = null
                showAddEditDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add profile")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (profiles.isEmpty()) {
                Text(
                    text = "No per-message profiles yet.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(profiles.values.sortedBy { it.shortcode }, key = { it.shortcode }) { profile ->
                        ProfileListItem(
                            profile = profile,
                            appViewModel = appViewModel,
                            onEdit = {
                                editingProfile = profile
                                showAddEditDialog = true
                            },
                            onDelete = { showDeleteConfirmFor = profile.shortcode }
                        )
                    }
                }
            }
        }
    }

    if (showAddEditDialog) {
        AddEditProfileDialog(
            existing = editingProfile,
            existingShortcodes = profiles.keys,
            appViewModel = appViewModel,
            onDismiss = { showAddEditDialog = false },
            onSave = { updated ->
                val newProfiles = profiles.toMutableMap()
                if (editingProfile != null && editingProfile!!.shortcode != updated.shortcode) {
                    newProfiles.remove(editingProfile!!.shortcode)
                }
                newProfiles[updated.shortcode] = updated
                saveProfiles(newProfiles)
                showAddEditDialog = false
            }
        )
    }

    showDeleteConfirmFor?.let { shortcode ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmFor = null },
            title = { Text("Delete profile") },
            text = { Text("Delete the \"$shortcode\" profile?") },
            confirmButton = {
                Button(
                    onClick = {
                        val newProfiles = profiles.toMutableMap()
                        newProfiles.remove(shortcode)
                        saveProfiles(newProfiles)
                        showDeleteConfirmFor = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmFor = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProfileListItem(
    profile: PerMessageProfileEntry,
    appViewModel: AppViewModel,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AvatarImage(
            mxcUrl = profile.avatarUrl.takeIf { it.isNotBlank() },
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            fallbackText = profile.displayname,
            size = 44.dp
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayname,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${profile.shortcode} — ${profile.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AddEditProfileDialog(
    existing: PerMessageProfileEntry?,
    existingShortcodes: Set<String>,
    appViewModel: AppViewModel,
    onDismiss: () -> Unit,
    onSave: (PerMessageProfileEntry) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var shortcode by remember { mutableStateOf(existing?.shortcode ?: "") }
    var displayname by remember { mutableStateOf(existing?.displayname ?: "") }
    var avatarUrl by remember { mutableStateOf(existing?.avatarUrl ?: "") }
    var avatarUploadInProgress by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
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
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = appViewModel.authToken,
                            isEncrypted = false,
                            compressOriginal = false
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = shortcode,
                    onValueChange = { shortcode = it.replace(" ", "").replace("\n", "") },
                    label = { Text("Shortcode (no spaces)") },
                    singleLine = true,
                    enabled = existing == null,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = displayname,
                    onValueChange = { displayname = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val previewHttpUrl = remember(avatarUrl) {
                        AvatarUtils.getFullImageUrl(context, avatarUrl, appViewModel.homeserverUrl)
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
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        AvatarImage(
                            mxcUrl = null,
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = appViewModel.authToken,
                            fallbackText = displayname.ifBlank { shortcode },
                            size = 44.dp
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        if (avatarUploadInProgress) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Uploading…", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Button(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Choose Avatar", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (avatarUrl.isNotBlank()) {
                            TextButton(
                                onClick = { avatarUrl = "" },
                                contentPadding = PaddingValues(0.dp)
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
                    val trimmedShortcode = shortcode.trim()
                    val trimmedDisplayname = displayname.trim()
                    if (trimmedShortcode.isBlank()) {
                        error = "Shortcode is required"
                        return@Button
                    }
                    if (trimmedShortcode.contains(" ")) {
                        error = "Shortcode must not contain spaces"
                        return@Button
                    }
                    if (trimmedDisplayname.isBlank()) {
                        error = "Display name is required"
                        return@Button
                    }
                    if (existing == null && trimmedShortcode in existingShortcodes) {
                        error = "A profile with this shortcode already exists"
                        return@Button
                    }
                    onSave(
                        PerMessageProfileEntry(
                            shortcode = trimmedShortcode,
                            id = existing?.id ?: trimmedShortcode,
                            displayname = trimmedDisplayname,
                            avatarUrl = avatarUrl
                        )
                    )
                },
                enabled = !avatarUploadInProgress
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { if (!avatarUploadInProgress) onDismiss() }) { Text("Cancel") }
        }
    )
}

/**
 * Floating per-message profile picker shown when the user types `/pmp` in the composer.
 * Displays all available profiles with avatar images; selecting one fills in the shortcode.
 */
@Composable
fun PerMessageProfilePicker(
    appViewModel: AppViewModel,
    onProfileSelected: (PerMessageProfileEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles = remember { readPerMessageProfiles().values.sortedBy { it.shortcode } }

    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 4.dp
    ) {
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No Per Message profile available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.height(220.dp),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(profiles, key = { it.shortcode }) { profile ->
                    PerMessageProfilePickerItem(
                        profile = profile,
                        appViewModel = appViewModel,
                        onSelected = { onProfileSelected(profile) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PerMessageProfilePickerItem(
    profile: PerMessageProfileEntry,
    appViewModel: AppViewModel,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AvatarImage(
            mxcUrl = profile.avatarUrl.takeIf { it.isNotBlank() },
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            fallbackText = profile.displayname,
            size = 36.dp
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayname,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = profile.shortcode,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
