package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.RoomItem
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.components.FullImageDialog


import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun usernameFromMatrixId(userId: String): String =
    userId.removePrefix("@").substringBefore(":")

/**
 * Helper function to navigate to user info screen with optional roomId
 */
fun NavController.navigateToUserInfo(userId: String, roomId: String? = null) {
    val encodedUserId = java.net.URLEncoder.encode(userId, "UTF-8")
    navigate("user_info/$encodedUserId") {
        launchSingleTop = true
    }
    // Set roomId in savedStateHandle after navigation
    currentBackStackEntry?.savedStateHandle?.set("roomId", roomId ?: "")
}

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
    roomId: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showFullAvatarDialog by remember { mutableStateOf(false) }
    var fullAvatarUrl by remember { mutableStateOf<String?>(null) }
    // State to hold user info
    var userProfileInfo by remember { mutableStateOf<UserProfileInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Dialog state
    var showDeviceListDialog by remember { mutableStateOf(false) }
    
    // Current time state for user's timezone
    var currentTimeInUserTz by remember { mutableStateOf("") }

    // Also check savedStateHandle for roomId (in case it was set during navigation)
    val roomIdFromState = remember {
        navController.currentBackStackEntry?.savedStateHandle?.get<String>("roomId")?.takeIf { it.isNotBlank() }
            ?: navController.currentBackStackEntry?.savedStateHandle?.get<String>("user_info_roomId")?.takeIf { it.isNotBlank() }
    }
    val effectiveRoomId = roomId ?: roomIdFromState
    
    // Request all user info when screen is created
    LaunchedEffect(userId, effectiveRoomId) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Requesting user info for $userId${if (effectiveRoomId != null) " in room $effectiveRoomId" else ""}")
        
        // OPTIMIZATION: Check room-specific cache first if effectiveRoomId is provided, otherwise check global cache
        val cachedProfile = appViewModel.getUserProfile(userId, roomId = effectiveRoomId)
        if (cachedProfile != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Found ${if (effectiveRoomId != null) "room-specific" else "cached"} profile for $userId")
            // Prefill with cached data while loading full info in background
            userProfileInfo = net.vrkknn.andromuks.utils.UserProfileInfo(
                userId = userId,
                displayName = cachedProfile.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(userId),
                avatarUrl = cachedProfile.avatarUrl,
                timezone = null,
                encryptionInfo = null,
                mutualRooms = emptyList()
            )
            isLoading = false // Show cached data immediately
        }
        
        // If roomId is provided, request room-specific profile from backend
        if (effectiveRoomId != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Requesting room-specific profile for $userId in room $effectiveRoomId")
            appViewModel.requestRoomSpecificUserProfile(effectiveRoomId, userId)
        }
        
        // Request full user info to get complete data (timezone, encryption, mutual rooms)
        // Note: requestFullUserInfo uses get_profile which returns global profile, but we'll
        // override displayName and avatarUrl with room-specific values if available
        appViewModel.requestFullUserInfo(userId) { profileInfo, error ->
            isLoading = false
            if (error != null) {
                errorMessage = error
                android.util.Log.e("Andromuks", "UserInfoScreen: Error loading user info: $error")
            } else {
                // Re-check room-specific profile in case it was updated from the backend request
                val roomSpecificProfile = if (effectiveRoomId != null) {
                    appViewModel.getUserProfile(userId, roomId = effectiveRoomId)
                } else {
                    null
                }
                
                // If we have room-specific profile data, use it for display name and avatar
                // but keep the global data for timezone, encryption, and mutual rooms
                val finalProfileInfo = if (effectiveRoomId != null && roomSpecificProfile != null && profileInfo != null) {
                    profileInfo.copy(
                        displayName = roomSpecificProfile.displayName?.takeIf { it.isNotBlank() } ?: profileInfo.displayName,
                        avatarUrl = roomSpecificProfile.avatarUrl ?: profileInfo.avatarUrl
                    )
                } else {
                    profileInfo
                }
                userProfileInfo = finalProfileInfo?.copy(
                    displayName = finalProfileInfo.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(userId)
                )
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Loaded user info successfully${if (effectiveRoomId != null && roomSpecificProfile != null) " (using room-specific display name/avatar)" else ""}")
            }
        }
    }
    
    // Also observe member updates to refresh UI when room-specific profile arrives
    LaunchedEffect(effectiveRoomId, appViewModel.memberUpdateCounter) {
        if (effectiveRoomId != null && userProfileInfo != null) {
            // Re-check room-specific profile when member cache updates
            val updatedRoomSpecificProfile = appViewModel.getUserProfile(userId, roomId = effectiveRoomId)
            if (updatedRoomSpecificProfile != null && userProfileInfo != null) {
                val currentProfile = userProfileInfo!!
                // Only update if profile data actually changed
                if (currentProfile.displayName != updatedRoomSpecificProfile.displayName ||
                    currentProfile.avatarUrl != updatedRoomSpecificProfile.avatarUrl) {
                    userProfileInfo = currentProfile.copy(
                        displayName = updatedRoomSpecificProfile.displayName ?: currentProfile.displayName,
                        avatarUrl = updatedRoomSpecificProfile.avatarUrl ?: currentProfile.avatarUrl
                    )
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Updated room-specific profile from backend response")
                }
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
                title = { Text("User Info") }
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
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clickable(enabled = userProfileInfo!!.avatarUrl != null) {
                            val avatarUrl = userProfileInfo!!.avatarUrl
                            if (!avatarUrl.isNullOrBlank()) {
                                val fullUrl = AvatarUtils.getFullImageUrl(
                                    context,
                                    avatarUrl,
                                    appViewModel.homeserverUrl
                                ) ?: AvatarUtils.getAvatarUrl(
                                    context,
                                    avatarUrl,
                                    appViewModel.homeserverUrl
                                )
                                
                                if (fullUrl != null) {
                                    fullAvatarUrl = fullUrl
                                    showFullAvatarDialog = true
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Full-size avatar unavailable",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    "User has no avatar",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AvatarImage(
                        mxcUrl = userProfileInfo!!.avatarUrl,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        fallbackText = userProfileInfo!!.displayName ?: usernameFromMatrixId(userId),
                        size = 160.dp,
                        userId = userId,
                        displayName = userProfileInfo!!.displayName
                    )
                }
                
                // User Display Name and Matrix ID - reduced spacing
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // User Display Name
                    Text(
                        text = userProfileInfo!!.displayName ?: usernameFromMatrixId(userId),
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
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Get DM room IDs for this user from m.direct
                    // Make reactive to account data and room list changes
                    val allRooms = appViewModel.allRooms
                    val updateCounter = appViewModel.updateCounter
                    val dmRoomIds = remember(userId, updateCounter) {
                        appViewModel.getDirectRoomIdsForUser(userId)
                    }
                    
                    // Check if we're joined to any of these rooms
                    // Make reactive to room list changes
                    val joinedDmRoomId = remember(dmRoomIds, allRooms) {
                        dmRoomIds.firstOrNull { roomId ->
                            appViewModel.getRoomById(roomId) != null
                        }
                    }
                    
                    val isDmAvailable = joinedDmRoomId != null
                    
                    Button(
                        onClick = {
                            if (isDmAvailable) {
                                val encodedRoomId = java.net.URLEncoder.encode(joinedDmRoomId, "UTF-8")
                                navController.navigate("room_timeline/$encodedRoomId")
                            }
                        },
                        enabled = isDmAvailable,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Go to DM")
                    }

                    Button(
                        onClick = {
                            val encInfo = userProfileInfo!!.encryptionInfo
                            if (encInfo != null && !encInfo.devicesTracked) {
                                // Track devices first
                                isLoading = true
                                appViewModel.trackUserDevices(userId) { updatedEncInfo, error ->
                                    isLoading = false
                                    if (error == null && updatedEncInfo != null) {
                                        userProfileInfo =
                                            userProfileInfo!!.copy(encryptionInfo = updatedEncInfo)
                                        showDeviceListDialog = true
                                    }
                                }
                            } else {
                                showDeviceListDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        val encInfo = userProfileInfo!!.encryptionInfo
                        val buttonText = when {
                            encInfo == null -> "No Encryption Info"
                            !encInfo.devicesTracked -> "Track Device List"
                            else -> "Device List (${encInfo.devices?.size ?: 0})"
                        }
                        Text(buttonText)
                    }
                }
                
                // Shared Rooms Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider()
                    
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
    
    if (showFullAvatarDialog && fullAvatarUrl != null) {
        FullImageDialog(
            imageUrl = fullAvatarUrl!!,
            authToken = appViewModel.authToken,
            onDismiss = { showFullAvatarDialog = false },
            contentDescription = userProfileInfo?.displayName ?: userId
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
                    HorizontalDivider()
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
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            
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

