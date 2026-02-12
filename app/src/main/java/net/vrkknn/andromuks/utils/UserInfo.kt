package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.BuildConfig
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.size.Precision
import coil.request.CachePolicy
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.MediaUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring


import org.json.JSONObject
import org.json.JSONArray
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
 * Data class for user pronouns
 */
data class UserPronouns(
    val language: String,
    val summary: String
)

/**
 * Data class for complete user profile info
 */
data class UserProfileInfo(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val timezone: String?,
    val pronouns: List<UserPronouns>?,
    val encryptionInfo: UserEncryptionInfo?,
    val mutualRooms: List<String>,
    val roomDisplayName: String? = null, // Per-room display name
    val roomAvatarUrl: String? = null, // Per-room avatar URL
    val arbitraryFields: Map<String, Any> = emptyMap() // All other profile fields not explicitly handled
)

/**
 * Composable to display an arbitrary profile field
 */
@Composable
fun ArbitraryFieldCard(key: String, value: Any) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Render value based on type
            when (value) {
                is org.json.JSONArray -> {
                    // Handle array of objects (like pronouns format)
                    if (value.length() > 0) {
                        val firstItem = value.optJSONObject(0)
                        if (firstItem != null && firstItem.has("language") && firstItem.has("summary")) {
                            // Format similar to pronouns
                            val items = mutableListOf<String>()
                            for (i in 0 until value.length()) {
                                val item = value.optJSONObject(i)
                                if (item != null) {
                                    val summary = item.optString("summary", "")
                                    if (summary.isNotBlank()) {
                                        items.add(summary)
                                    }
                                }
                            }
                            if (items.isNotEmpty()) {
                                Text(
                                    text = items.joinToString(", "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = value.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            // Generic array
                            Text(
                                text = value.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "[]",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is org.json.JSONObject -> {
                    Text(
                        text = value.toString(2), // Pretty print with 2-space indent
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is String -> {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is Number -> {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is Boolean -> {
                    Text(
                        text = if (value) "Yes" else "No",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * User Info Screen - displays detailed information about a user
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun UserInfoScreen(
    userId: String,
    navController: NavController,
    appViewModel: AppViewModel,
    roomId: String? = null,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,  // ← ADD THIS
    animatedVisibilityScope: AnimatedVisibilityScope? = null  
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showFullAvatarDialog by remember { mutableStateOf(false) }
    var fullAvatarUrl by remember { mutableStateOf<String?>(null) }
    var viewingGlobalAvatar by remember { mutableStateOf(false) } // Track which avatar is being viewed
    // State to hold user info
    var userProfileInfo by remember { mutableStateOf<UserProfileInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Dialog state
    var showDeviceListDialog by remember { mutableStateOf(false) }
    var showSharedRoomsDialog by remember { mutableStateOf(false) }
    
    // Current time state for user's timezone
    var currentTimeInUserTz by remember { mutableStateOf("") }

    // Also check savedStateHandle for roomId (in case it was set during navigation)
    val roomIdFromState = remember {
        navController.currentBackStackEntry?.savedStateHandle?.get<String>("roomId")?.takeIf { it.isNotBlank() }
            ?: navController.currentBackStackEntry?.savedStateHandle?.get<String>("user_info_roomId")?.takeIf { it.isNotBlank() }
    }
    val effectiveRoomId = roomId ?: roomIdFromState
    
    // CRITICAL FIX: Always request fresh profile data from backend (never use cache)
    // This ensures we get the latest profile info including pronouns, timezone, etc.
    LaunchedEffect(userId, effectiveRoomId) {
        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Requesting FRESH user info for $userId${if (effectiveRoomId != null) " in room $effectiveRoomId" else ""} (bypassing cache)")
        
        // Always request fresh data - don't use cached profile
        // Request full user info to get complete data (timezone, pronouns, encryption, mutual rooms)
        // This always makes a fresh get_profile request to the backend
        appViewModel.requestFullUserInfo(userId, forceRefresh = true) { profileInfo, error ->
            isLoading = false
            if (error != null) {
                errorMessage = error
                android.util.Log.e("Andromuks", "UserInfoScreen: Error loading user info: $error")
            } else {
                userProfileInfo = profileInfo?.copy(
                    displayName = profileInfo.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(userId)
                )
                if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Loaded fresh user info successfully with pronouns: ${profileInfo?.pronouns?.size ?: 0}, timezone: ${profileInfo?.timezone}")
                
                // Request per-room profile if we have a roomId
                if (effectiveRoomId != null && profileInfo != null) {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Requesting per-room profile for $userId in room $effectiveRoomId")
                    appViewModel.requestPerRoomMemberState(effectiveRoomId, userId) { roomDisplayName, roomAvatarUrl ->
                        if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "UserInfoScreen: Received per-room profile - displayName: $roomDisplayName, avatarUrl: $roomAvatarUrl")
                        userProfileInfo = userProfileInfo?.copy(
                            roomDisplayName = roomDisplayName,
                            roomAvatarUrl = roomAvatarUrl
                        )
                    }
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
                ExpressiveLoadingIndicator()
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
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // User Avatar - made larger (use room avatar if different from global, otherwise global)
                val roomAvatarUrl = userProfileInfo!!.roomAvatarUrl?.takeIf { !it.isNullOrBlank() }
                val globalAvatarUrl = userProfileInfo!!.avatarUrl?.takeIf { !it.isNullOrBlank() }
                val hasRoomSpecificAvatar = roomAvatarUrl != null && roomAvatarUrl != globalAvatarUrl
                val avatarUrlToUse = if (hasRoomSpecificAvatar) roomAvatarUrl else globalAvatarUrl
                
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clickable(enabled = avatarUrlToUse != null) {
                            if (!avatarUrlToUse.isNullOrBlank()) {
                                val fullUrl = AvatarUtils.getFullImageUrl(
                                    context,
                                    avatarUrlToUse,
                                    appViewModel.homeserverUrl
                                ) ?: AvatarUtils.getAvatarUrl(
                                    context,
                                    avatarUrlToUse,
                                    appViewModel.homeserverUrl
                                )
                                
                                if (fullUrl != null) {
                                    fullAvatarUrl = fullUrl
                                    viewingGlobalAvatar = false // Main avatar (room-specific if available)
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
                    val roomDisplayName = userProfileInfo!!.roomDisplayName?.takeIf { !it.isNullOrBlank() }
                    val globalDisplayName = userProfileInfo!!.displayName?.takeIf { !it.isNullOrBlank() }
                    val hasRoomSpecificDisplayName = roomDisplayName != null && roomDisplayName != globalDisplayName
                    val displayNameForAvatar = if (hasRoomSpecificDisplayName) roomDisplayName else (globalDisplayName ?: usernameFromMatrixId(userId))
                    
                    // Main avatar (room-specific if available, otherwise global)
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            AvatarImage(
                                mxcUrl = avatarUrlToUse,
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = appViewModel.authToken,
                                fallbackText = displayNameForAvatar,
                                size = 220.dp,
                                userId = userId,
                                displayName = displayNameForAvatar,
                                modifier = Modifier.sharedElement(
                                    rememberSharedContentState(key = "user-avatar-$userId"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    }
                                )
                            )
                        }
                    } else {
                        // Fallback without shared element
                        AvatarImage(
                            mxcUrl = avatarUrlToUse,
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = appViewModel.authToken,
                            fallbackText = displayNameForAvatar,
                            size = 220.dp,
                            userId = userId,
                            displayName = displayNameForAvatar
                        )
                    }
                    
                    // Show global avatar as badge in top-right corner if we have room-specific avatar
                    if (hasRoomSpecificAvatar && globalAvatarUrl != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(80.dp) // Badge size
                                .padding(4.dp)
                                .clickable(enabled = true) {
                                    // Open global avatar in viewer
                                    val fullUrl = AvatarUtils.getFullImageUrl(
                                        context,
                                        globalAvatarUrl,
                                        appViewModel.homeserverUrl
                                    ) ?: AvatarUtils.getAvatarUrl(
                                        context,
                                        globalAvatarUrl,
                                        appViewModel.homeserverUrl
                                    )
                                    
                                    if (fullUrl != null) {
                                        fullAvatarUrl = fullUrl
                                        viewingGlobalAvatar = true // Global avatar badge
                                        showFullAvatarDialog = true
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Full-size avatar unavailable",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                        ) {
                            Surface(
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                AvatarImage(
                                    mxcUrl = globalAvatarUrl,
                                    homeserverUrl = appViewModel.homeserverUrl,
                                    authToken = appViewModel.authToken,
                                    fallbackText = globalDisplayName ?: usernameFromMatrixId(userId),
                                    size = 80.dp,
                                    userId = userId,
                                    displayName = globalDisplayName
                                )
                            }
                        }
                    }
                }
                
                // User Display Name and Matrix ID - reduced spacing
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Per-room display name (if available) or global display name
                    val roomDisplayName = userProfileInfo!!.roomDisplayName?.takeIf { !it.isNullOrBlank() }
                    val globalDisplayName = userProfileInfo!!.displayName?.takeIf { !it.isNullOrBlank() }
                    val roomAvatarUrl = userProfileInfo!!.roomAvatarUrl?.takeIf { !it.isNullOrBlank() }
                    val globalAvatarUrl = userProfileInfo!!.avatarUrl?.takeIf { !it.isNullOrBlank() }
                    
                    // Determine if we have a room-specific profile (different from global)
                    val hasRoomSpecificDisplayName = roomDisplayName != null && roomDisplayName != globalDisplayName
                    val hasRoomSpecificAvatar = roomAvatarUrl != null && roomAvatarUrl != globalAvatarUrl
                    val hasRoomSpecificProfile = hasRoomSpecificDisplayName || hasRoomSpecificAvatar
                    
                    // Use room-specific if available and different, otherwise use global
                    val displayNameToShow = if (hasRoomSpecificDisplayName) roomDisplayName else (globalDisplayName ?: usernameFromMatrixId(userId))
                    
                    Text(
                        text = displayNameToShow,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    
                    // Show room-specific indicator ONLY if we have a room-specific profile (different from global)
                    if (hasRoomSpecificProfile) {
                        Text(
                            text = "Room-specific profile",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        // Also show global display name if different
                        if (globalDisplayName != null && globalDisplayName != displayNameToShow) {
                            Text(
                                text = "Global: $globalDisplayName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    // Matrix User ID
                    Text(
                        text = userId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                
                // Pronouns and Timezone on the same line
                val pronouns = userProfileInfo!!.pronouns
                val hasPronouns = pronouns != null && pronouns.isNotEmpty()
                val hasTimezone = userProfileInfo!!.timezone != null && currentTimeInUserTz.isNotEmpty()
                
                if (hasPronouns || hasTimezone) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Pronouns
                        if (hasPronouns) {
                            val pronounsList = pronouns!!
                            val languages = pronounsList.map { it.language }.distinct()
                            val pronounsText = pronounsList.joinToString(", ") { it.summary }
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Pronouns",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    if (languages.isNotEmpty()) {
                                        Text(
                                            text = "Language: ${languages.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    Text(
                                        text = pronounsText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                        
                        // Time in user's timezone
                        if (hasTimezone) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Timezone",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = userProfileInfo!!.timezone!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = currentTimeInUserTz,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
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
                    
                    // Shared Rooms Button
                    Button(
                        onClick = { showSharedRoomsDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Shared Rooms (${userProfileInfo!!.mutualRooms.size})")
                    }
                }
                
                // Arbitrary profile fields
                val arbitraryFields = userProfileInfo!!.arbitraryFields
                if (arbitraryFields.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = "Additional Profile Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        arbitraryFields.toSortedMap().forEach { (key, value) ->
                            ArbitraryFieldCard(key = key, value = value)
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
        val avatarMxcUrl = if (viewingGlobalAvatar) {
            userProfileInfo?.avatarUrl
        } else {
            userProfileInfo?.roomAvatarUrl ?: userProfileInfo?.avatarUrl
        }
        val displayName = if (viewingGlobalAvatar) {
            userProfileInfo?.displayName ?: userId
        } else {
            userProfileInfo?.roomDisplayName ?: userProfileInfo?.displayName ?: userId
        }
        
        AvatarViewerDialog(
            imageUrl = fullAvatarUrl!!,
            avatarMxcUrl = avatarMxcUrl,
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            displayName = displayName,
            onDismiss = { 
                showFullAvatarDialog = false
                viewingGlobalAvatar = false
            }
        )
    }
    
    // Shared Rooms Dialog
    if (showSharedRoomsDialog && userProfileInfo != null) {
        SharedRoomsDialog(
            mutualRooms = userProfileInfo!!.mutualRooms,
            appViewModel = appViewModel,
            navController = navController,
            onDismiss = { showSharedRoomsDialog = false }
        )
    }
}

/**
 * Dialog to display shared rooms list
 */
@Composable
fun SharedRoomsDialog(
    mutualRooms: List<String>,
    appViewModel: AppViewModel,
    navController: NavController,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text("Shared Rooms (${mutualRooms.size})")
        },
        text = {
            if (mutualRooms.isEmpty()) {
                Text(
                    text = "No shared rooms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(mutualRooms) { roomId ->
                        SharedRoomItem(
                            roomId = roomId,
                            appViewModel = appViewModel,
                            navController = navController
                        )
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
            .clickable {
                val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                navController.navigate("room_timeline/$encodedRoomId")
            }
            .padding(vertical = 6.dp, horizontal = 8.dp),
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

/**
 * Avatar viewer dialog with rotation and download support
 */
@Composable
fun AvatarViewerDialog(
    imageUrl: String,
    avatarMxcUrl: String?,
    homeserverUrl: String,
    authToken: String,
    displayName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val imageLoader = remember { ImageLoaderSingleton.get(context) }
    
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    
    // Animate rotation smoothly
    val animatedRotation by animateFloatAsState(
        targetValue = rotationDegrees,
        animationSpec = tween(durationMillis = 300),
        label = "rotation"
    )
    val normalizedRotation = (animatedRotation % 360f + 360f) % 360f
    
    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        val panScale = scale
        val maxPan = 4000f * scale
        offsetX = (offsetX + offsetChange.x * panScale).coerceIn(-maxPan, maxPan)
        offsetY = (offsetY + offsetChange.y * panScale).coerceIn(-maxPan, maxPan)
    }
    
    // Check for cached file
    var cachedFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(avatarMxcUrl) {
        if (avatarMxcUrl != null) {
            cachedFile = IntelligentMediaCache.getCachedFile(context, avatarMxcUrl)
        }
    }
    
    val finalImageUrl = remember(imageUrl, cachedFile) {
        cachedFile?.absolutePath ?: imageUrl
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
        ) {
            // Image with zoom, pan, and rotation
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                        rotationZ = normalizedRotation
                    )
                    .transformable(state = transformableState)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Reset zoom and pan on tap
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        )
                    }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(finalImageUrl)
                        .apply {
                            if (cachedFile == null && finalImageUrl.startsWith("http")) {
                                addHeader("Cookie", "gomuks_auth=$authToken")
                            }
                        }
                        .size(Size.ORIGINAL)
                        .precision(Precision.EXACT)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Top toolbar with action buttons
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Rotate Left button
                IconButton(
                    onClick = {
                        rotationDegrees = rotationDegrees - 90f
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.RotateLeft,
                        contentDescription = "Rotate Left",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // Rotate Right button
                IconButton(
                    onClick = {
                        rotationDegrees = rotationDegrees + 90f
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = "Rotate Right",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // Save button
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            saveAvatarToGallery(
                                context = context,
                                cachedFile = cachedFile,
                                imageUrl = finalImageUrl,
                                filename = "${displayName}_avatar.jpg",
                                authToken = authToken
                            )
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = "Save to Gallery",
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                // Close button
                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Save avatar image to gallery
 */
private suspend fun saveAvatarToGallery(
    context: Context,
    cachedFile: File?,
    imageUrl: String,
    filename: String,
    authToken: String
) = withContext(Dispatchers.IO) {
    try {
        var imageFile: File? = cachedFile
        
        // Download if needed
        if (imageFile == null && imageUrl.startsWith("http")) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(imageUrl)
                .addHeader("Cookie", "gomuks_auth=$authToken")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    val tempFile = File(context.cacheDir, "temp_avatar_${System.currentTimeMillis()}.jpg")
                    java.io.FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                    imageFile = tempFile
                }
            }
        } else if (imageFile == null && imageUrl.startsWith("/")) {
            imageFile = File(imageUrl)
        }
        
        if (imageFile == null || !imageFile!!.exists()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save avatar", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }
        
        // Save to MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Andromuks")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: throw Exception("Failed to create MediaStore entry")
        
        // Copy file
        context.contentResolver.openOutputStream(uri)?.use { output ->
            imageFile!!.inputStream().use { input ->
                input.copyTo(output)
            }
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
        }
        
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Avatar saved to gallery", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e("Andromuks", "Error saving avatar to gallery", e)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Error saving avatar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

