package net.vrkknn.andromuks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlin.math.roundToInt

enum class RoomPreset(val value: String, val displayName: String) {
    PUBLIC_CHAT("public_chat", "Public"),
    PRIVATE_CHAT("private_chat", "Private"),
    TRUSTED_PRIVATE_CHAT("trusted_private_chat", "Trusted Private Chat")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomMakerScreen(
    appViewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val serverName = remember(appViewModel.currentUserId) {
        appViewModel.currentUserId.substringAfter(":", "").takeIf { it.isNotBlank() } ?: "server.com"
    }

    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var encrypted by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(RoomPreset.PRIVATE_CHAT) }
    var presetExpanded by remember { mutableStateOf(false) }
    var inviteUsers by remember { mutableStateOf(emptyList<String>()) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var isDirect by remember { mutableStateOf(false) }
    var roomVersion by remember { mutableFloatStateOf(11f) }
    var roomIdOrTs by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successRoomId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Room") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Alias") },
                    prefix = { Text("#") },
                    suffix = { Text(":$serverName") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Encrypted", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = encrypted, onCheckedChange = { encrypted = it })
                }
            }
            item {
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it }
                ) {
                    OutlinedTextField(
                        value = preset.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Preset") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = presetExpanded,
                        onDismissRequest = { presetExpanded = false }
                    ) {
                        RoomPreset.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.displayName) },
                                onClick = { preset = p; presetExpanded = false }
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Invite Users",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { inviteUsers = inviteUsers + "" }) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = "Add User")
                    }
                }
            }
            itemsIndexed(inviteUsers) { index, user ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { newVal ->
                            inviteUsers = inviteUsers.toMutableList().also { it[index] = newVal }
                        },
                        placeholder = { Text("@user:server.com") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(onClick = {
                        inviteUsers = inviteUsers.toMutableList().also { it.removeAt(index) }
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove")
                    }
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Advanced", style = MaterialTheme.typography.titleSmall)
                    Icon(
                        if (advancedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
            }
            if (advancedExpanded) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Direct Chat", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = isDirect, onCheckedChange = { isDirect = it })
                    }
                }
                item {
                    val versionInt = roomVersion.roundToInt()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Room Version: $versionInt", style = MaterialTheme.typography.bodyLarge)
                        Slider(
                            value = roomVersion,
                            onValueChange = { roomVersion = it },
                            valueRange = 1f..12f,
                            steps = 10,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                item {
                    val isV12 = roomVersion.roundToInt() == 12
                    OutlinedTextField(
                        value = roomIdOrTs,
                        onValueChange = { roomIdOrTs = it },
                        label = { Text(if (isV12) "Create Timestamp" else "Room ID") },
                        placeholder = {
                            Text(if (isV12) System.currentTimeMillis().toString() else "!meow:$serverName")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = if (isV12) KeyboardOptions(keyboardType = KeyboardType.Number)
                        else KeyboardOptions.Default
                    )
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (isCreating) return@Button
                        isCreating = true
                        val versionInt = roomVersion.roundToInt()
                        val isV12 = versionInt == 12
                        val filteredInvites = inviteUsers.filter { it.isNotBlank() }
                        val initialState = mutableListOf<Map<String, Any>>()
                        if (encrypted) {
                            initialState.add(
                                mapOf(
                                    "type" to "m.room.encryption",
                                    "content" to mapOf("algorithm" to "m.megolm.v1.aes-sha2")
                                )
                            )
                        }
                        appViewModel.createRoom(
                            name = name.trim().takeIf { it.isNotBlank() },
                            topic = topic.trim().takeIf { it.isNotBlank() },
                            roomAliasName = alias.trim().takeIf { it.isNotBlank() },
                            preset = preset.value,
                            isDirect = isDirect,
                            invite = filteredInvites,
                            initialState = initialState,
                            roomVersion = versionInt.toString(),
                            roomId = if (!isV12 && roomIdOrTs.isNotBlank()) roomIdOrTs.trim() else null,
                            originServerTs = if (isV12 && roomIdOrTs.isNotBlank()) roomIdOrTs.trim().toLongOrNull() else null
                        ) { roomId, error ->
                            isCreating = false
                            if (error != null) {
                                errorMessage = error
                            } else {
                                successRoomId = roomId
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCreating
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Create Room")
                }
            }
        }
    }

    errorMessage?.let { err ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Error") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("OK") }
            }
        )
    }

    successRoomId?.let { roomId ->
        AlertDialog(
            onDismissRequest = {
                successRoomId = null
                navController.popBackStack()
            },
            title = { Text("Room Created") },
            text = { Text("Room ID: $roomId") },
            confirmButton = {
                TextButton(onClick = {
                    successRoomId = null
                    navController.popBackStack()
                }) { Text("OK") }
            }
        )
    }
}
