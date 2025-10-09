package net.vrkknn.andromuks.utils

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.ui.components.AvatarImage
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * Data class for user encryption info
 */
data class UserEncryptionInfo(
    val devicesTracked: Boolean,
    val devices: List<DeviceInfo>?,
    val masterKey: String?,
    val firstMasterKey: String?,
    val userTrusted: Boolean,
    val errors: Any?
)

/**
 * Data class for a single device
 */
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val identityKey: String,
    val signingKey: String,
    val fingerprint: String,
    val trustState: String
)

/**
 * Data class for complete user profile info
 */
data class UserProfileInfo(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val timezone: String?,
    val encryptionInfo: UserEncryptionInfo?,
    val mutualRooms: List<String>
)

/**
 * User Info Screen - displays detailed information about a user
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserInfoScreen(
    userId: String,
    navController: NavController,
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    // State to hold user info
    var userProfileInfo by remember { mutableStateOf<UserProfileInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Dialog state
    var showDeviceListDialog by remember { mutableStateOf(false) }
    
    // Current time state for user's timezone
    var currentTimeInUserTz by remember { mutableStateOf("") }
    
    // Request all user info when screen is created
    LaunchedEffect(userId) {
        android.util.Log.d("Andromuks", "UserInfoScreen: Requesting user info for $userId")
        appViewModel.requestFullUserInfo(userId) { profileInfo, error ->
            isLoading = false
            if (error != null) {
                errorMessage = error
                android.util.Log.e("Andromuks", "UserInfoScreen: Error loading user info: $error")
            } else {
                userProfileInfo = profileInfo
                android.util.Log.d("Andromuks", "UserInfoScreen: Loaded user info successfully")
            }
        }
    }
    
    // Update time every second if timezone is available
    LaunchedEffect(userProfileInfo?.timezone) {
        while (true) {
            userProfileInfo?.timezone?.let { tz ->
                try {
                    val zoneId = ZoneId.of(tz)
                    val now = ZonedDateTime.now(zoneId)
                    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                    currentTimeInUserTz = now.format(formatter)
                } catch (e: Exception) {
                    currentTimeInUserTz = "Invalid timezone"
                }
            }
            delay(1000)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Info") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Error: $errorMessage",
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else if (userProfileInfo != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // User Avatar - made bigger
                AvatarImage(
                    mxcUrl = userProfileInfo!!.avatarUrl,
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = appViewModel.authToken,
                    fallbackText = userProfileInfo!!.displayName ?: userId,
                    size = 160.dp,
                    userId = userId,
                    displayName = userProfileInfo!!.displayName
                )
                
                // User Display Name and Matrix ID - reduced spacing
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // User Display Name
                    Text(
                        text = userProfileInfo!!.displayName ?: userId,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    
                    // Matrix User ID
                    Text(
                        text = userId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                
                // Time in user's timezone
                if (userProfileInfo!!.timezone != null && currentTimeInUserTz.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = currentTimeInUserTz,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = userProfileInfo!!.timezone!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                
                // Device List Button
                Button(
                    onClick = {
                        val encInfo = userProfileInfo!!.encryptionInfo
                        if (encInfo != null && !encInfo.devicesTracked) {
                            // Track devices first
                            isLoading = true
                            appViewModel.trackUserDevices(userId) { updatedEncInfo, error ->
                                isLoading = false
                                if (error == null && updatedEncInfo != null) {
                                    userProfileInfo = userProfileInfo!!.copy(encryptionInfo = updatedEncInfo)
                                    showDeviceListDialog = true
                                }
                            }
                        } else {
                            showDeviceListDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val encInfo = userProfileInfo!!.encryptionInfo
                    val buttonText = when {
                        encInfo == null -> "No Encryption Info"
                        !encInfo.devicesTracked -> "Track Device List"
                        else -> "Device List (${encInfo.devices?.size ?: 0})"
                    }
                    Text(buttonText)
                }
                
                // Shared Rooms Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Divider()
                    
                    Text(
                        text = "Shared Rooms (${userProfileInfo!!.mutualRooms.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Scrollable room list in a card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(userProfileInfo!!.mutualRooms) { roomId ->
                                SharedRoomItem(
                                    roomId = roomId,
                                    appViewModel = appViewModel,
                                    navController = navController
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Device List Dialog
    if (showDeviceListDialog && userProfileInfo?.encryptionInfo?.devices != null) {
        DeviceListDialog(
            encryptionInfo = userProfileInfo!!.encryptionInfo!!,
            userId = userId,
            onDismiss = { showDeviceListDialog = false }
        )
    }
}

/**
 * Composable for a single shared room item
 * Shows up to 3 lines: display name, canonical alias (if available), room ID
 */
@Composable
fun SharedRoomItem(
    roomId: String,
    appViewModel: AppViewModel,
    navController: NavController
) {
    val room = appViewModel.getRoomById(roomId)
    
    // Check if this is the currently loaded room to get canonical alias
    val canonicalAlias = if (appViewModel.currentRoomState?.roomId == roomId) {
        appViewModel.currentRoomState?.canonicalAlias
    } else {
        null
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        AvatarImage(
            mxcUrl = room?.avatarUrl,
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            fallbackText = room?.name ?: roomId,
            size = 40.dp,
            userId = roomId,
            displayName = room?.name
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            // Line 1: Room display name (aligned with avatar top)
            Text(
                text = room?.name ?: roomId,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // Line 2: Canonical alias (if available and room has a name)
            if (canonicalAlias != null && room?.name != null) {
                Text(
                    text = canonicalAlias,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Line 3: Room ID (always shown if we have a room name)
            if (room?.name != null) {
                Text(
                    text = roomId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Dialog to display device list and encryption info
 */
@Composable
fun DeviceListDialog(
    encryptionInfo: UserEncryptionInfo,
    userId: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Column {
                Text("Encryption Info")
                Text(
                    text = userId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Master key info
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Master Key Info",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            
                            if (!encryptionInfo.masterKey.isNullOrBlank()) {
                                Text(
                                    text = "Master Key:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = encryptionInfo.masterKey,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            
                            if (!encryptionInfo.firstMasterKey.isNullOrBlank() && 
                                encryptionInfo.firstMasterKey != encryptionInfo.masterKey) {
                                Text(
                                    text = "First Master Key:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                Text(
                                    text = encryptionInfo.firstMasterKey,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            
                            Text(
                                text = "User Trusted: ${if (encryptionInfo.userTrusted) "Yes" else "No"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (encryptionInfo.userTrusted) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                
                // Devices header
                item {
                    Divider()
                    Text(
                        text = "Devices (${encryptionInfo.devices?.size ?: 0})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Device list
                if (encryptionInfo.devices != null) {
                    items(encryptionInfo.devices) { device ->
                        DeviceInfoCard(device = device)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Card displaying information about a single device
 */
@Composable
fun DeviceInfoCard(device: DeviceInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Device name and ID
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "ID: ${device.deviceId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Trust state badge
            Surface(
                color = when (device.trustState) {
                    "verified" -> MaterialTheme.colorScheme.primaryContainer
                    "cross-signed-tofu" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                },
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = device.trustState,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            
            // Fingerprint
            Text(
                text = "Fingerprint:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = device.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            
            // Identity Key
            Text(
                text = "Identity Key:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = device.identityKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // Signing Key
            Text(
                text = "Signing Key:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = device.signingKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

