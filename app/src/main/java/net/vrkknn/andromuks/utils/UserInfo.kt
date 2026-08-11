package net.vrkknn.andromuks.utils

import android.Manifest
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract.RawContacts.DefaultAccount
import android.provider.ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.Morph
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vrkknn.andromuks.AppViewModel
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.ContactsSyncService
import net.vrkknn.andromuks.MatrixUser
import net.vrkknn.andromuks.RoomTimelineCache
import net.vrkknn.andromuks.ui.components.AvatarImage
import net.vrkknn.andromuks.ui.theme.scaledTweenMs
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.utils.IntelligentMediaCache
import net.vrkknn.andromuks.utils.MediaUploadUtils
import net.vrkknn.andromuks.utils.MediaUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.BreakIterator
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Get the default contact account for creating new contacts
 * 
 * Android 14+ requires using the default account when a cloud account is set.
 * This function detects the default account and falls back gracefully.
 * 
 * @return Pair of (accountName, accountType) or (null, null) if no account available
 */
private fun getDefaultContactAccount(context: Context): Pair<String?, String?> {
    // Android 15+ (API 35) provides direct API to get default account
    if (Build.VERSION.SDK_INT >= 35) {
        try {
            val defaultAccountAndState: DefaultAccountAndState =
                DefaultAccount.getDefaultAccountForNewContacts(context.contentResolver)

            // .account is only non-null for STATE_CLOUD or STATE_SIM
            val account = defaultAccountAndState.account
            if (account != null) {
                if (BuildConfig.DEBUG) {
                    Log.d("Andromuks", "Using default contact account: ${account.name} (${account.type})")
                }
                return Pair(account.name, account.type)
            }
        } catch (e: Exception) {
            Log.e("Andromuks", "Error getting default contact account", e)
        }
    }

    // Fallback: first Google account
    val accountManager = AccountManager.get(context)
    val googleAccount = accountManager.getAccountsByType("com.google").firstOrNull()
    if (googleAccount != null) {
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "Using Google account: ${googleAccount.name}")
        }
        return Pair(googleAccount.name, "com.google")
    }

    // Last resort: any non-local syncing account
    val anyAccount = accountManager.accounts.firstOrNull { it.type != "local" }
    if (anyAccount != null) {
        if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "Using any available account: ${anyAccount.name} (${anyAccount.type})")
        }
        return Pair(anyAccount.name, anyAccount.type)
    }

    // No account available - return null (local account)
    // Note: This may fail on Android 14+ if a cloud account is set as default
    if (BuildConfig.DEBUG) {
        Log.w("Andromuks", "No account available, will attempt to use local account (may fail on Android 14+)")
    }
    return Pair(null, null)
}

/**
 * Helper function to navigate to user info screen with optional roomId and eventId
 */
fun NavController.navigateToUserInfo(userId: String, roomId: String? = null, eventId: String? = null) {
    val encodedUserId = java.net.URLEncoder.encode(userId, "UTF-8")
    val encodedEventId = eventId?.let { java.net.URLEncoder.encode(it, "UTF-8") }

    // Build route - use route with eventId if available, otherwise use simple route
    val route = if (encodedEventId != null) {
        "user_info/$encodedUserId/$encodedEventId"
    } else {
        "user_info/$encodedUserId"
    }

    if (BuildConfig.DEBUG) {
        android.util.Log.d("Andromuks", "navigateToUserInfo: Navigating to $route (userId=$userId, eventId=$eventId)")
    }

    navigate(route)
    // Set roomId in savedStateHandle
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
    val errors: Any?,
)

/**
 * Data class for a single device
 */
data class DeviceInfo(val deviceId: String, val name: String, val identityKey: String, val signingKey: String, val fingerprint: String, val trustState: String)

/**
 * Data class for user pronouns
 */
data class UserPronouns(val language: String, val summary: String)

internal data class ProfileStatus(val text: String, val emoji: String, val sourceKey: String)

internal data class ProfileCall(val callJoinedTs: Long?, val sourceKey: String)

internal fun valueToJsonObject(value: Any?): JSONObject? = when (value) {
    is JSONObject -> value

    is Map<*, *> -> {
        val obj = JSONObject()
        value.entries.forEach { (key, v) ->
            if (key is String) {
                obj.put(key, v)
            }
        }
        obj
    }

    else -> null
}

internal fun extractProfileStatus(arbitraryFields: Map<String, Any>): ProfileStatus? {
    val keys = listOf("m.status", "org.msc.4426.status", "org.msc4426.status")
    keys.forEach { key ->
        val obj = valueToJsonObject(arbitraryFields[key]) ?: return@forEach
        val text = obj.optString("text", "")
        val emoji = obj.optString("emoji", "")
        if (text.isNotBlank() && emoji.isNotBlank()) {
            return ProfileStatus(text = text, emoji = emoji, sourceKey = key)
        }
    }
    return null
}

internal fun extractProfileCall(arbitraryFields: Map<String, Any>): ProfileCall? {
    val keys = listOf("m.call", "org.msc.4426.call", "org.msc4426.call")
    keys.forEach { key ->
        val obj = valueToJsonObject(arbitraryFields[key]) ?: return@forEach
        val ts = if (obj.has("call_joined_ts")) obj.optLong("call_joined_ts") else null
        return ProfileCall(callJoinedTs = ts, sourceKey = key)
    }
    return null
}

/**
 * Data class for profile banner
 */
internal data class ProfileBanner(val mxcUrl: String, val sourceKey: String)

/**
 * Data class for profile bio with optional HTML formatting
 */
internal data class ProfileBio(val body: String, val isHtml: Boolean, val sourceKey: String, val editSource: String? = null)

/** Source key for the MSC4440 biography, which the backend delivers pre-rendered. */
internal const val SPEC_BIO_SOURCE_KEY = "gay.fomx.biography"

/** Section heading for a bio, which differs per vendor field. */
internal fun bioLabelFor(sourceKey: String): String = when (sourceKey) {
    SPEC_BIO_SOURCE_KEY -> "Bio"
    "chat.commet.profile_bio" -> "About"
    "moe.sable.app.bio" -> "Bio"
    else -> "About"
}

/**
 * How tall the bio card's body is allowed to get in the profile screen. A bio is arbitrary markup
 * — it can carry images and run for pages — and it must not push the rest of the profile off
 * screen, so the card is capped here and the full thing is a tap away in [ExpandedBioDialog].
 */
private val BIO_COLLAPSED_MAX_HEIGHT = 140.dp

/** Height of the fade drawn over the bottom of a bio that was cut off by the cap above. */
private val BIO_FADE_HEIGHT = 40.dp

/** gomuks input-format prefix meaning "what follows is HTML, not markdown". */
internal const val GOMUKS_HTML_INPUT_PREFIX = "/html "

/**
 * Extract profile banner mxc URL from arbitrary profile fields
 * Supports: chat.commet.profile_banner
 */
internal fun extractProfileBanner(arbitraryFields: Map<String, Any>): ProfileBanner? {
    val key = "chat.commet.profile_banner"
    val value = arbitraryFields[key]
    if (value is String && value.startsWith("mxc://")) {
        return ProfileBanner(mxcUrl = value, sourceKey = key)
    }
    return null
}

/**
 * Extract all profile bios to display.
 *
 * [specBio] is the MSC4440 biography, already rendered to HTML by the backend — it comes first
 * when present. The remaining two are vendor fields we also understand:
 * chat.commet.profile_bio (with format detection) and moe.sable.app.bio (HTML). gomuks does
 * not aggregate these, so a user can genuinely have more than one and all are displayed.
 */
internal fun extractProfileBios(arbitraryFields: Map<String, Any>, specBio: ProfileBioContent? = null): List<ProfileBio> {
    val bios = mutableListOf<ProfileBio>()

    if (specBio != null) {
        bios.add(
            ProfileBio(
                body = specBio.html,
                isHtml = true,
                sourceKey = SPEC_BIO_SOURCE_KEY,
                editSource = specBio.editSource,
            ),
        )
    }

    // Try chat.commet.profile_bio (it has format info)
    val commetBio = valueToJsonObject(arbitraryFields["chat.commet.profile_bio"])
    if (commetBio != null) {
        val format = commetBio.optString("format", "")
        val formattedBody = commetBio.optString("formatted_body", "")
        val body = commetBio.optString("body", "")

        if (formattedBody.isNotBlank() && format == "org.matrix.custom.html") {
            bios.add(ProfileBio(body = formattedBody, isHtml = true, sourceKey = "chat.commet.profile_bio"))
        } else if (body.isNotBlank()) {
            bios.add(ProfileBio(body = body, isHtml = false, sourceKey = "chat.commet.profile_bio"))
        }
    }

    // Try moe.sable.app.bio (always HTML)
    val sableBio = arbitraryFields["moe.sable.app.bio"]
    if (sableBio is String && sableBio.isNotBlank()) {
        bios.add(ProfileBio(body = sableBio, isHtml = true, sourceKey = "moe.sable.app.bio"))
    }

    return bios
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun rememberMorphingExpressiveAvatarMaskModifier(): Modifier {
    val shapes = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons
    if (shapes.size < 2) return Modifier

    val infiniteTransition = rememberInfiniteTransition(label = "avatar_morph_transition")
    val morphCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "avatar_morph_cycle",
    )
    val shapeRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "avatar_mask_rotation",
    )

    val segmentCount = shapes.size
    val scaled = morphCycle * segmentCount
    val fromIndex = kotlin.math.floor(scaled).toInt().mod(segmentCount)
    val toIndex = (fromIndex + 1).mod(segmentCount)
    val localProgress = scaled - kotlin.math.floor(scaled)
    val morph = remember(fromIndex, toIndex) { Morph(shapes[fromIndex], shapes[toIndex]) }
    val rawPath = remember(morph, localProgress) {
        morph.toPath(progress = localProgress)
    }

    return Modifier.drawWithContent {
        val bounds = rawPath.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f) {
            drawContent()
            return@drawWithContent
        }
        val scale = kotlin.math.min(size.width / bounds.width, size.height / bounds.height)
        val dx = (size.width - bounds.width * scale) / 2f - bounds.left * scale
        val dy = (size.height - bounds.height * scale) / 2f - bounds.top * scale

        val transformedPath = Path().apply {
            addPath(rawPath)
            transform(
                Matrix().apply {
                    translate(dx, dy)
                    scale(scale, scale)
                },
            )
            val cx = size.width / 2f
            val cy = size.height / 2f
            transform(
                Matrix().apply {
                    translate(cx, cy)
                    rotateZ(shapeRotation)
                    translate(-cx, -cy)
                },
            )
        }

        clipPath(transformedPath) {
            this@drawWithContent.drawContent()
        }
    }
}

private fun isSingleGrapheme(input: String): Boolean {
    if (input.isBlank()) return false
    val trimmed = input.trim()
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(trimmed)
    var graphemeCount = 0
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        val next = iterator.next()
        if (next == BreakIterator.DONE) break
        graphemeCount++
        if (graphemeCount > 1) return false
        boundary = next
    }
    return graphemeCount == 1
}

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
    val arbitraryFields: Map<String, Any> = emptyMap(), // All other profile fields not explicitly handled
    val bio: ProfileBioContent? = null, // MSC4440 biography, rendered by the backend
)

/**
 * Composable to display an arbitrary profile field
 */
@Composable
fun ArbitraryFieldCard(key: String, value: Any) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = value.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            // Generic array
                            Text(
                                text = value.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = "[]",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is org.json.JSONObject -> {
                    Text(
                        text = value.toString(2), // Pretty print with 2-space indent
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is String -> {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Number -> {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Boolean -> {
                    Text(
                        text = if (value) "Yes" else "No",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    eventId: String? = null, // Add eventId parameter for shared transitions
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null, // ← ADD THIS
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showFullAvatarDialog by remember { mutableStateOf(false) }
    // mxc:// URL of the avatar/banner to view. ImageViewerDialog resolves it to a full-size HTTP URL
    // and handles cache lookup, so the click sites store the mxc directly (never a resolved HTTP URL).
    var fullAvatarMxc by remember { mutableStateOf<String?>(null) }
    // State to hold user info
    var userProfileInfo by remember { mutableStateOf<UserProfileInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Check if user is already in contacts
    var isUserInContacts by remember { mutableStateOf(false) }

    // Function to check if user is in contacts
    fun checkContactStatus() {
        val myUserId = appViewModel.currentUserId
        val isOwnProfile = myUserId.isNotBlank() && userId == myUserId
        if (!isOwnProfile) {
            val hasReadContacts = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) == PackageManager.PERMISSION_GRANTED

            if (hasReadContacts) {
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        val syncService = ContactsSyncService(
                            context,
                            accountName = "Andromuks",
                            accountType = "net.vrkknn.andromuks.matrix",
                        )
                        isUserInContacts = syncService.isUserInContacts(userId)
                    }
                }
            }
        }
    }

    // Contacts permission launcher (needs both READ and WRITE)
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val hasReadContacts = permissions[Manifest.permission.READ_CONTACTS] == true
        val hasWriteContacts = permissions[Manifest.permission.WRITE_CONTACTS] == true

        // Re-check contact status after permissions are granted
        if (hasReadContacts) {
            checkContactStatus()
        }

        if (hasReadContacts && hasWriteContacts && userProfileInfo != null) {
            coroutineScope.launch {
                addMatrixUserToContacts(
                    context = context,
                    userId = userId,
                    displayName = userProfileInfo!!.displayName
                        ?: usernameFromMatrixId(userId),
                    avatarUrl = userProfileInfo!!.avatarUrl,
                    homeserverUrl = appViewModel.homeserverUrl,
                    authToken = appViewModel.authToken,
                )
                // Check if contact was successfully added and update state
                withContext(Dispatchers.IO) {
                    val syncService = ContactsSyncService(
                        context,
                        accountName = "Andromuks",
                        accountType = "net.vrkknn.andromuks.matrix",
                    )
                    val wasAdded = syncService.isUserInContacts(userId)
                    if (wasAdded) {
                        isUserInContacts = true
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Contact saved successfully",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        } else if (!hasReadContacts || !hasWriteContacts) {
            Toast.makeText(
                context,
                "Contacts permissions are required to add contact",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Dialog state
    var showDeviceListDialog by remember { mutableStateOf(false) }
    var showSharedRoomsDialog by remember { mutableStateOf(false) }

    // Moderation dialog state
    var showKickDialog by remember { mutableStateOf(false) }
    var showBanDialog by remember { mutableStateOf(false) }
    var showRedactDialog by remember { mutableStateOf(false) }
    var kickReason by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }
    var redactReason by remember { mutableStateOf("") }
    var banRedactRecentMessages by remember { mutableStateOf(false) } // OFF by default
    var banRedactSystemMessages by remember { mutableStateOf(true) }

    // Ignore dialog state
    var showIgnoreDialog by remember { mutableStateOf(false) }
    var isUserIgnored by remember { mutableStateOf(false) }
    var showStatusEditDialog by remember { mutableStateOf(false) }
    var showStatusEmojiPicker by remember { mutableStateOf(false) }
    var statusEmojiInput by remember { mutableStateOf("") }
    var statusTextInput by remember { mutableStateOf("") }
    var statusEditError by remember { mutableStateOf<String?>(null) }
    var showPronounsEditDialog by remember { mutableStateOf(false) }
    var pronounsLanguageInput by remember { mutableStateOf("") }
    var pronounsSummaryInput by remember { mutableStateOf("") }
    var pronounsEditError by remember { mutableStateOf<String?>(null) }
    var showTimezoneEditDialog by remember { mutableStateOf(false) }
    var timezoneInput by remember { mutableStateOf("") }
    val allTimezones = remember { TimeZone.getAvailableIDs().toList().sorted() }
    var showAddProfileInfoDialog by remember { mutableStateOf(false) }
    var showBannerEditDialog by remember { mutableStateOf(false) }
    var bannerUploadInProgress by remember { mutableStateOf(false) }
    var bannerUploadError by remember { mutableStateOf<String?>(null) }
    var showBioEditDialog by remember { mutableStateOf(false) }
    var bioInput by remember { mutableStateOf("") }
    var bioEditError by remember { mutableStateOf<String?>(null) }
    // Which bio the edit dialog writes back to: the MSC4440 biography or the commet vendor field.
    var bioEditTarget by remember { mutableStateOf(SPEC_BIO_SOURCE_KEY) }
    // Non-null while the full-size bio viewer is open; holds the bio being viewed.
    var expandedBio by remember { mutableStateOf<ProfileBio?>(null) }

    // Current time state for user's timezone
    var currentTimeInUserTz by remember { mutableStateOf("") }

    // Also check savedStateHandle for roomId (in case it was set during navigation)
    val roomIdFromState = remember {
        navController.currentBackStackEntry?.savedStateHandle?.get<String>("roomId")?.takeIf { it.isNotBlank() }
            ?: navController.currentBackStackEntry?.savedStateHandle?.get<String>(
                "user_info_roomId",
            )?.takeIf { it.isNotBlank() }
    }
    val effectiveRoomId = roomId ?: roomIdFromState

    // Get eventId from route arguments or parameter for shared transition key
    // Prefer parameter (passed from MainActivity), fallback to route arguments
    val eventIdFromRoute = eventId ?: remember {
        navController.currentBackStackEntry?.arguments?.getString("eventId")?.takeIf { it.isNotBlank() }
    }
    val sharedAvatarKey = remember(userId, eventIdFromRoute) {
        if (eventIdFromRoute != null) {
            "user-avatar-$eventIdFromRoute-$userId"
        } else {
            "user-avatar-$userId"
        }
    }
    LaunchedEffect(sharedAvatarKey, eventIdFromRoute, userId) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "UserInfo: Using shared key: $sharedAvatarKey (eventIdFromRoute=$eventIdFromRoute, userId=$userId, sharedScope=${sharedTransitionScope != null}, animatedScope=${animatedVisibilityScope != null}, isLoading=$isLoading)",
            )
        }
    }
    var lastLoggedAvatarBounds by remember(sharedAvatarKey) { mutableStateOf<String?>(null) }
    val sharedAvatarTransitionDurationMs = 380L
    val minimumMorphVisibleMs = 180L
    val minimumLoadingDurationMs = sharedAvatarTransitionDurationMs + minimumMorphVisibleMs
    var loadingStartedAtMs by remember(sharedAvatarKey) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(sharedAvatarKey) {
        loadingStartedAtMs = SystemClock.elapsedRealtime()
    }
    // Show morph overlay only after the shared transition has had time to settle.
    // This preserves a clean avatar flight from timeline -> final slot.
    var showMorphOverlay by remember(isLoading, sharedAvatarKey) { mutableStateOf(false) }
    LaunchedEffect(isLoading, sharedAvatarKey) {
        if (isLoading) {
            showMorphOverlay = false
            delay(sharedAvatarTransitionDurationMs)
            showMorphOverlay = true
        } else {
            showMorphOverlay = false
        }
    }
    val myUserId = appViewModel.currentUserId
    val isOwnProfile = myUserId.isNotBlank() && userId == myUserId

    // Check contact status when userId changes
    LaunchedEffect(userId) {
        checkContactStatus()
    }

    // Power levels for the moderation buttons, read straight from the per-room store.
    //
    // This used to request room state and then poll appViewModel.getRoomState() five times at 300ms
    // — against roomStatesCache, which is never written, so the loop always ran its full 1.5s and
    // always ended with null. The buttons only ever appeared when the profile was opened from the
    // room that happened to be open, because that path read currentRoomState instead.
    //
    // The store answers for any room, and is snapshot-backed, so a response arriving later
    // recomposes this without a polling loop.
    val roomPowerLevels = effectiveRoomId?.let {
        net.vrkknn.andromuks.utils.RoomStateStore.getParsed(it)?.powerLevels
    }

    // Refresh regardless of what the cache had: the stored copy is always treated as possibly
    // stale. requestRoomState de-duplicates in-flight requests, so this cannot pile up.
    LaunchedEffect(effectiveRoomId) {
        if (effectiveRoomId != null) {
            appViewModel.requestRoomState(effectiveRoomId)
        }
    }

    // Debug logging for moderation buttons visibility
    LaunchedEffect(effectiveRoomId, myUserId, userId, roomPowerLevels) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "UserInfoScreen: Moderation buttons check - effectiveRoomId=$effectiveRoomId, myUserId=$myUserId, userId=$userId, roomPowerLevels=${roomPowerLevels != null}, willShow=${effectiveRoomId != null && myUserId != userId}",
            )
        }
    }

    // Moderation actions. Kick and ban additionally require outranking the target; redact does not
    // — it is a flat check against the room's redact level.
    val canKick = remember(roomPowerLevels, myUserId, userId) {
        RoomPermissions.canKick(roomPowerLevels, myUserId, userId)
    }

    val canBan = remember(roomPowerLevels, myUserId, userId) {
        RoomPermissions.canBan(roomPowerLevels, myUserId, userId)
    }

    val canRedact = remember(roomPowerLevels, myUserId) {
        RoomPermissions.canRedactAsModerator(roomPowerLevels, myUserId)
    }

    // CRITICAL FIX: Always request fresh profile data from backend (never use cache)
    // Check if user is ignored
    LaunchedEffect(userId) {
        isUserIgnored = appViewModel.isUserIgnored(userId)
    }

    // This ensures we get the latest profile info including pronouns, timezone, etc.
    LaunchedEffect(userId, effectiveRoomId) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Andromuks",
                "UserInfoScreen: Requesting FRESH user info for $userId${if (effectiveRoomId != null) " in room $effectiveRoomId" else ""} (bypassing cache)",
            )
        }

        // Always request fresh data - don't use cached profile
        // Request full user info to get complete data (timezone, pronouns, encryption, mutual rooms)
        // This always makes a fresh get_profile request to the backend
        appViewModel.requestFullUserInfo(userId, forceRefresh = true) { profileInfo, error ->
            coroutineScope.launch {
                val elapsed = SystemClock.elapsedRealtime() - loadingStartedAtMs
                val remaining = (minimumLoadingDurationMs - elapsed).coerceAtLeast(0L)
                if (remaining > 0L) delay(remaining)
                isLoading = false
            }
            if (error != null) {
                errorMessage = error
                android.util.Log.e("Andromuks", "UserInfoScreen: Error loading user info: $error")
            } else {
                userProfileInfo = profileInfo?.copy(
                    displayName = profileInfo.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(userId),
                )
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Andromuks",
                        "UserInfoScreen: Loaded fresh user info successfully with pronouns: ${profileInfo?.pronouns?.size ?: 0}, timezone: ${profileInfo?.timezone}",
                    )
                }

                // Request per-room profile if we have a roomId
                if (effectiveRoomId != null && profileInfo != null) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Andromuks",
                            "UserInfoScreen: Requesting per-room profile for $userId in room $effectiveRoomId",
                        )
                    }
                    appViewModel.requestPerRoomMemberState(effectiveRoomId, userId) { roomDisplayName, roomAvatarUrl ->
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Andromuks",
                                "UserInfoScreen: Received per-room profile - displayName: $roomDisplayName, avatarUrl: $roomAvatarUrl",
                            )
                        }
                        userProfileInfo = userProfileInfo?.copy(
                            roomDisplayName = roomDisplayName,
                            roomAvatarUrl = roomAvatarUrl,
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
                title = { Text("User Info") },
                navigationIcon = {
                    IconButton(onClick = {
                        // If opened from external app (like Contacts), finish activity instead of navigating back
                        if (appViewModel.openedFromExternalApp) {
                            (context as? android.app.Activity)?.finish()
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (isOwnProfile) {
                        IconButton(onClick = { showAddProfileInfoDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add profile info",
                            )
                        }
                    } else {
                        // Show AccountCircle icon if contact exists (opens in Contacts app), otherwise Save icon (adds contact)
                        IconButton(
                            onClick = {
                                if (isUserInContacts) {
                                    // Open contact in Contacts app
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            val syncService = ContactsSyncService(
                                                context,
                                                accountName = "Andromuks",
                                                accountType = "net.vrkknn.andromuks.matrix",
                                            )
                                            val contactUri = syncService.getContactUri(userId)
                                            withContext(Dispatchers.Main) {
                                                if (contactUri != null) {
                                                    try {
                                                        val intent = Intent(Intent.ACTION_VIEW, contactUri)
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        Log.e("Andromuks", "Failed to open contact in Contacts app", e)
                                                        Toast.makeText(
                                                            context,
                                                            "Failed to open contact",
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    }
                                                } else {
                                                    // Contact was deleted, update state
                                                    isUserInContacts = false
                                                    Toast.makeText(
                                                        context,
                                                        "Contact not found",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Add contact to Android Contacts
                                    // Check permissions first (need both READ and WRITE)
                                    val hasReadContacts = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.READ_CONTACTS,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    val hasWriteContacts = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.WRITE_CONTACTS,
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (hasReadContacts && hasWriteContacts) {
                                        coroutineScope.launch {
                                            addMatrixUserToContacts(
                                                context = context,
                                                userId = userId,
                                                displayName = userProfileInfo?.displayName
                                                    ?: usernameFromMatrixId(userId),
                                                avatarUrl = userProfileInfo?.avatarUrl,
                                                homeserverUrl = appViewModel.homeserverUrl,
                                                authToken = appViewModel.authToken,
                                            )
                                            // Check if contact was successfully added and update state
                                            withContext(Dispatchers.IO) {
                                                val syncService = ContactsSyncService(
                                                    context,
                                                    accountName = "Andromuks",
                                                    accountType = "net.vrkknn.andromuks.matrix",
                                                )
                                                val wasAdded = syncService.isUserInContacts(userId)
                                                if (wasAdded) {
                                                    isUserInContacts = true
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                            context,
                                                            "Contact saved successfully",
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Request both permissions
                                        contactsPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.READ_CONTACTS,
                                                Manifest.permission.WRITE_CONTACTS,
                                            ),
                                        )
                                    }
                                }
                            },
                            enabled = true, // Always enabled - either opens contact or adds it
                        ) {
                            Icon(
                                imageVector = if (isUserInContacts) Icons.Filled.AccountCircle else Icons.Filled.Save,
                                contentDescription = if (isUserInContacts) "Open in Contacts" else "Save to Contacts",
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        if (isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Keep the shared avatar target in composition during loading so the
                // transition can match immediately from the tapped timeline avatar.
                val cachedProfile = appViewModel.getUserProfile(userId, effectiveRoomId ?: "")
                val loadingDisplayName =
                    cachedProfile?.displayName?.takeIf { it.isNotBlank() } ?: usernameFromMatrixId(userId)
                val loadingAvatarUrl = cachedProfile?.avatarUrl
                val morphingMaskModifier = rememberMorphingExpressiveAvatarMaskModifier()
                Box(
                    modifier = Modifier.size(128.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            AvatarImage(
                                mxcUrl = loadingAvatarUrl,
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = appViewModel.authToken,
                                fallbackText = loadingDisplayName,
                                size = 120.dp,
                                userId = userId,
                                displayName = loadingDisplayName,
                                modifier = Modifier.sharedElement(
                                    rememberSharedContentState(key = sharedAvatarKey),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        tween(durationMillis = scaledTweenMs(380), easing = LinearEasing)
                                    },
                                    renderInOverlayDuringTransition = true,
                                    zIndexInOverlay = 1f,
                                )
                                    .graphicsLayer {
                                        // Once morph overlay is visible, hide the base shared avatar
                                        // so the clipping effect remains visible.
                                        alpha = if (showMorphOverlay) 0f else 1f
                                    }
                                    .onGloballyPositioned { coords ->
                                        if (BuildConfig.DEBUG) {
                                            val b = coords.boundsInWindow()
                                            val token = "${b.left.toInt()},${b.top.toInt()},${b.width.toInt()},${b.height.toInt()},loading=true"
                                            if (token != lastLoggedAvatarBounds) {
                                                lastLoggedAvatarBounds = token
                                                android.util.Log.d(
                                                    "Andromuks",
                                                    "UserInfo: shared avatar bounds (loading) key=$sharedAvatarKey bounds=(${b.left.toInt()},${b.top.toInt()}) ${b.width.toInt()}x${b.height.toInt()}",
                                                )
                                            }
                                        }
                                    },
                            )
                        }
                    } else {
                        AvatarImage(
                            mxcUrl = loadingAvatarUrl,
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = appViewModel.authToken,
                            fallbackText = loadingDisplayName,
                            size = 120.dp,
                            userId = userId,
                            displayName = loadingDisplayName,
                            modifier = Modifier,
                        )
                    }
                    // Morph overlay appears after shared transition settles.
                    // It is rendered in the exact final slot and removed when loading ends.
                    if (showMorphOverlay) {
                        AvatarImage(
                            mxcUrl = loadingAvatarUrl,
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = appViewModel.authToken,
                            fallbackText = loadingDisplayName,
                            size = 120.dp,
                            userId = userId,
                            displayName = loadingDisplayName,
                            modifier = Modifier.then(morphingMaskModifier),
                        )
                    }
                }
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Error: $errorMessage",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (userProfileInfo != null) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Profile Banner (if available) with Avatar overlay
                val profileBanner = extractProfileBanner(userProfileInfo!!.arbitraryFields)

                // User Avatar - made larger (use room avatar if different from global, otherwise global)
                val roomAvatarUrl = userProfileInfo!!.roomAvatarUrl?.takeIf { !it.isNullOrBlank() }
                val globalAvatarUrl = userProfileInfo!!.avatarUrl?.takeIf { !it.isNullOrBlank() }
                val hasRoomSpecificAvatar = roomAvatarUrl != null && roomAvatarUrl != globalAvatarUrl
                val avatarUrlToUse = if (hasRoomSpecificAvatar) roomAvatarUrl else globalAvatarUrl

                // Banner + Avatar section
                if (profileBanner != null) {
                    // Banner image URL for click handler
                    val bannerHttpUrl = remember(profileBanner.mxcUrl, appViewModel.homeserverUrl) {
                        MediaUtils.mxcToHttpUrl(profileBanner.mxcUrl, appViewModel.homeserverUrl)
                    }

                    LaunchedEffect(profileBanner.mxcUrl, bannerHttpUrl) {
                        if (BuildConfig.DEBUG) {
                            Log.d("Andromuks", "UserInfo: Banner mxc=${profileBanner.mxcUrl}, http=$bannerHttpUrl")
                        }
                    }

                    // Parent container for banner + avatar
                    // Height = 136dp (non-overlapping banner) + 128dp (avatar) = 264dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(264.dp),
                    ) {
                        // Banner image (visual only, 200dp tall, but positioned at top)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            if (bannerHttpUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(bannerHttpUrl)
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .build(),
                                    imageLoader = ImageLoaderSingleton.get(context),
                                    contentDescription = "Profile banner",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            // Gradient overlay for better avatar visibility
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                            ),
                                            startY = 100f,
                                        ),
                                    ),
                            )

                            // Edit button for own profile (top-right corner)
                            if (isOwnProfile) {
                                IconButton(
                                    onClick = {
                                        bannerUploadError = null
                                        showBannerEditDialog = true
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(32.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                            CircleShape,
                                        ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "Edit banner",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }

                        // Clickable area for banner - only the top part that doesn't overlap with avatar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(136.dp)
                                .clickable {
                                    if (bannerHttpUrl != null) {
                                        if (BuildConfig.DEBUG) {
                                            Log.d("Andromuks", "UserInfo: Banner clicked, opening: ${profileBanner.mxcUrl}")
                                        }
                                        fullAvatarMxc = profileBanner.mxcUrl
                                        showFullAvatarDialog = true
                                    }
                                },
                        )

                        // Avatar positioned using Column + Spacer (preserves size and moves hit area)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(modifier = Modifier.height(136.dp))
                            Box(
                                modifier = Modifier
                                    .size(128.dp)
                                    .clickable(enabled = avatarUrlToUse != null) {
                                        if (!avatarUrlToUse.isNullOrBlank()) {
                                            fullAvatarMxc = avatarUrlToUse
                                            showFullAvatarDialog = true
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                val roomDisplayName = userProfileInfo!!.roomDisplayName?.takeIf { !it.isNullOrBlank() }
                                val globalDisplayName = userProfileInfo!!.displayName?.takeIf { !it.isNullOrBlank() }
                                val hasRoomSpecificDisplayName =
                                    roomDisplayName != null && roomDisplayName != globalDisplayName
                                val displayNameForAvatar = if (hasRoomSpecificDisplayName) {
                                    roomDisplayName
                                } else {
                                    (
                                        globalDisplayName
                                            ?: usernameFromMatrixId(
                                                userId,
                                            )
                                        )
                                }

                                // Border around avatar for better visibility on banner
                                Surface(
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .border(4.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                    color = MaterialTheme.colorScheme.surface,
                                ) {
                                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                        with(sharedTransitionScope) {
                                            AvatarImage(
                                                mxcUrl = avatarUrlToUse,
                                                homeserverUrl = appViewModel.homeserverUrl,
                                                authToken = appViewModel.authToken,
                                                fallbackText = displayNameForAvatar,
                                                size = 120.dp,
                                                userId = userId,
                                                displayName = displayNameForAvatar,
                                                modifier = Modifier.sharedElement(
                                                    rememberSharedContentState(key = sharedAvatarKey),
                                                    animatedVisibilityScope = animatedVisibilityScope,
                                                    boundsTransform = { _, _ ->
                                                        tween(durationMillis = scaledTweenMs(380), easing = LinearEasing)
                                                    },
                                                    renderInOverlayDuringTransition = true,
                                                    zIndexInOverlay = 1f,
                                                ),
                                            )
                                        }
                                    } else {
                                        AvatarImage(
                                            mxcUrl = avatarUrlToUse,
                                            homeserverUrl = appViewModel.homeserverUrl,
                                            authToken = appViewModel.authToken,
                                            fallbackText = displayNameForAvatar,
                                            size = 120.dp,
                                            userId = userId,
                                            displayName = displayNameForAvatar,
                                        )
                                    }
                                }

                                // Show global avatar as badge if we have room-specific avatar
                                if (hasRoomSpecificAvatar && globalAvatarUrl != null) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(48.dp)
                                            .clickable {
                                                if (!globalAvatarUrl.isNullOrBlank()) {
                                                    fullAvatarMxc = globalAvatarUrl
                                                    showFullAvatarDialog = true
                                                }
                                            },
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                            color = MaterialTheme.colorScheme.surface,
                                        ) {
                                            AvatarImage(
                                                mxcUrl = globalAvatarUrl,
                                                homeserverUrl = appViewModel.homeserverUrl,
                                                authToken = appViewModel.authToken,
                                                fallbackText = globalDisplayName ?: usernameFromMatrixId(userId),
                                                size = 48.dp,
                                                userId = userId,
                                                displayName = globalDisplayName,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Regular avatar section (when no banner)
                if (profileBanner == null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .size(128.dp)
                            .clickable(enabled = avatarUrlToUse != null) {
                                if (!avatarUrlToUse.isNullOrBlank()) {
                                    fullAvatarMxc = avatarUrlToUse
                                    showFullAvatarDialog = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val roomDisplayName = userProfileInfo!!.roomDisplayName?.takeIf { !it.isNullOrBlank() }
                        val globalDisplayName = userProfileInfo!!.displayName?.takeIf { !it.isNullOrBlank() }
                        val hasRoomSpecificDisplayName = roomDisplayName != null && roomDisplayName != globalDisplayName
                        val displayNameForAvatar = if (hasRoomSpecificDisplayName) {
                            roomDisplayName
                        } else {
                            (
                                globalDisplayName
                                    ?: usernameFromMatrixId(
                                        userId,
                                    )
                                )
                        }

                        // Keep sharedElement in loaded state too so destination node remains stable
                        // across fast loading transitions. Morph overlay is loading-only.
                        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                            with(sharedTransitionScope) {
                                AvatarImage(
                                    mxcUrl = avatarUrlToUse,
                                    homeserverUrl = appViewModel.homeserverUrl,
                                    authToken = appViewModel.authToken,
                                    fallbackText = displayNameForAvatar,
                                    size = 120.dp,
                                    userId = userId,
                                    displayName = displayNameForAvatar,
                                    modifier = Modifier.sharedElement(
                                        rememberSharedContentState(key = sharedAvatarKey),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        boundsTransform = { _, _ ->
                                            tween(durationMillis = scaledTweenMs(380), easing = LinearEasing)
                                        },
                                        renderInOverlayDuringTransition = true,
                                        zIndexInOverlay = 1f,
                                    )
                                        .onGloballyPositioned { coords ->
                                            if (BuildConfig.DEBUG) {
                                                val b = coords.boundsInWindow()
                                                val token = "${b.left.toInt()},${b.top.toInt()},${b.width.toInt()},${b.height.toInt()},loading=false"
                                                if (token != lastLoggedAvatarBounds) {
                                                    lastLoggedAvatarBounds = token
                                                    android.util.Log.d(
                                                        "Andromuks",
                                                        "UserInfo: shared avatar bounds (loaded) key=$sharedAvatarKey bounds=(${b.left.toInt()},${b.top.toInt()}) ${b.width.toInt()}x${b.height.toInt()}",
                                                    )
                                                }
                                            }
                                        },
                                )
                            }
                        } else {
                            AvatarImage(
                                mxcUrl = avatarUrlToUse,
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = appViewModel.authToken,
                                fallbackText = displayNameForAvatar,
                                size = 120.dp,
                                userId = userId,
                                displayName = displayNameForAvatar,
                            )
                        }

                        // Show global avatar as badge in top-right corner if we have room-specific avatar
                        if (hasRoomSpecificAvatar && globalAvatarUrl != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(56.dp) // Badge size
                                    .padding(4.dp)
                                    .clickable(enabled = true) {
                                        // Open global avatar in viewer
                                        if (!globalAvatarUrl.isNullOrBlank()) {
                                            fullAvatarMxc = globalAvatarUrl
                                            showFullAvatarDialog = true
                                        }
                                    },
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                    color = MaterialTheme.colorScheme.surface,
                                ) {
                                    AvatarImage(
                                        mxcUrl = globalAvatarUrl,
                                        homeserverUrl = appViewModel.homeserverUrl,
                                        authToken = appViewModel.authToken,
                                        fallbackText = globalDisplayName ?: usernameFromMatrixId(userId),
                                        size = 56.dp,
                                        userId = userId,
                                        displayName = globalDisplayName,
                                    )
                                }
                            }
                        }
                    }
                } // End of profileBanner == null block

                // User Display Name and Matrix ID - reduced spacing
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    val displayNameToShow = if (hasRoomSpecificDisplayName) {
                        roomDisplayName
                    } else {
                        (
                            globalDisplayName
                                ?: usernameFromMatrixId(
                                    userId,
                                )
                            )
                    }

                    Text(
                        text = displayNameToShow,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    // Show room-specific indicator ONLY if we have a room-specific profile (different from global)
                    if (hasRoomSpecificProfile) {
                        Text(
                            text = "Room-specific profile",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        // Also show global display name if different
                        if (globalDisplayName != null && globalDisplayName != displayNameToShow) {
                            Text(
                                text = "Global: $globalDisplayName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    // Matrix User ID
                    Text(
                        text = userId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    val profileStatus = extractProfileStatus(userProfileInfo!!.arbitraryFields)
                    if (profileStatus != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${profileStatus.emoji} ${profileStatus.text}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isOwnProfile) {
                                    IconButton(
                                        onClick = {
                                            statusEmojiInput = profileStatus.emoji
                                            statusTextInput = profileStatus.text
                                            statusEditError = null
                                            showStatusEditDialog = true
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Edit status",
                                        )
                                    }
                                }
                            }
                        }
                    } else if (isOwnProfile) {
                        TextButton(
                            onClick = {
                                statusEmojiInput = ""
                                statusTextInput = ""
                                statusEditError = null
                                showStatusEditDialog = true
                            },
                        ) {
                            Text("Set status")
                        }
                    }

                    val profileCall = extractProfileCall(userProfileInfo!!.arbitraryFields)
                    if (profileCall != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "In call",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                if (profileCall.callJoinedTs != null && profileCall.callJoinedTs > 0L) {
                                    Text(
                                        text = "Joined: ${profileCall.callJoinedTs}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                // Pronouns and Timezone on the same line
                val pronouns = userProfileInfo!!.pronouns
                val hasPronouns = pronouns != null && pronouns.isNotEmpty()
                val hasTimezone = userProfileInfo!!.timezone != null && currentTimeInUserTz.isNotEmpty()

                if (hasPronouns || hasTimezone) {
                    Row(
                        // The root Column has no horizontal padding — the profile banner is
                        // full-bleed — so every content sibling carries the 16.dp gutter itself,
                        // matching the name/status block above.
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Pronouns
                        if (hasPronouns) {
                            val pronounsList = pronouns
                            val languages = pronounsList.map { it.language }.distinct()
                            val pronounsText = pronounsList.joinToString(", ") { it.summary }
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Pronouns",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        if (isOwnProfile) {
                                            IconButton(
                                                onClick = {
                                                    pronounsLanguageInput = pronounsList.firstOrNull()?.language ?: "en"
                                                    pronounsSummaryInput = pronounsList.firstOrNull()?.summary ?: ""
                                                    pronounsEditError = null
                                                    showPronounsEditDialog = true
                                                },
                                                modifier = Modifier.size(20.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Edit,
                                                    contentDescription = "Edit pronouns",
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            }
                                        }
                                    }
                                    if (languages.isNotEmpty()) {
                                        Text(
                                            text = "Language: ${languages.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                    Text(
                                        text = pronounsText,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }

                        // Time in user's timezone
                        if (hasTimezone) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Timezone",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        if (isOwnProfile) {
                                            IconButton(
                                                onClick = {
                                                    timezoneInput = userProfileInfo!!.timezone ?: ""
                                                    showTimezoneEditDialog = true
                                                },
                                                modifier = Modifier.size(20.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Edit,
                                                    contentDescription = "Edit timezone",
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = userProfileInfo!!.timezone!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Text(
                                        text = currentTimeInUserTz,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                // Profile Bio section (MSC4440 biography, chat.commet.profile_bio and/or moe.sable.app.bio)
                val profileBios = extractProfileBios(userProfileInfo!!.arbitraryFields, userProfileInfo!!.bio)
                profileBios.forEach { profileBio ->
                    val bioLabel = bioLabelFor(profileBio.sourceKey)
                    // The backend only sends edit_source for our own profile, so its presence
                    // is what makes the standard bio editable.
                    val isEditableBio = profileBio.sourceKey == "chat.commet.profile_bio" ||
                        (profileBio.sourceKey == SPEC_BIO_SOURCE_KEY && profileBio.editSource != null)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = bioLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                if (isOwnProfile && isEditableBio) {
                                    IconButton(
                                        onClick = {
                                            // Pre-populate with the markdown source: edit_source for
                                            // the standard bio (body there is rendered HTML), the
                                            // stored body for the commet one.
                                            bioInput = profileBio.editSource ?: profileBio.body
                                            bioEditTarget = profileBio.sourceKey
                                            bioEditError = null
                                            showBioEditDialog = true
                                        },
                                        modifier = Modifier.size(20.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Edit bio",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }

                            // The body is capped and clipped: tapping anywhere that isn't an
                            // interactive span (a link, a pill, an image) opens the full bio in a
                            // floating window. Those spans consume the tap themselves, so they
                            // keep working here.
                            val density = LocalDensity.current
                            val collapsedHeightPx = with(density) { BIO_COLLAPSED_MAX_HEIGHT.roundToPx() }
                            var bioIsClipped by remember(profileBio.body) { mutableStateOf(false) }
                            val containerColor = MaterialTheme.colorScheme.secondaryContainer
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = BIO_COLLAPSED_MAX_HEIGHT)
                                    .clipToBounds()
                                    .clickable { expandedBio = profileBio },
                            ) {
                                BoxWithConstraints(
                                    // Measured unbounded so the full body's height is known even
                                    // though the parent clips it — that is what tells us whether
                                    // anything was actually cut off.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                        .onSizeChanged { size ->
                                            bioIsClipped = size.height > collapsedHeightPx
                                        },
                                ) {
                                    ProfileBioBody(
                                        profileBio = profileBio,
                                        contentWidth = maxWidth,
                                        // A preview: an image may fill the card but must not be
                                        // able to demand more room than the card itself has.
                                        maxImageHeight = BIO_COLLAPSED_MAX_HEIGHT,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        homeserverUrl = appViewModel.homeserverUrl,
                                        authToken = appViewModel.authToken,
                                        onMatrixUserClick = { clickedUserId ->
                                            navController.navigateToUserInfo(clickedUserId, roomId)
                                        },
                                    )
                                }
                                if (bioIsClipped) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(BIO_FADE_HEIGHT)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, containerColor),
                                                ),
                                            ),
                                        contentAlignment = Alignment.BottomCenter,
                                    ) {
                                        Text(
                                            text = "Tap to read more",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Get DM room IDs for this user from m.direct
                // Make reactive to account data and room list changes
                val allRooms = appViewModel.allRooms
                val dmRoomIds = remember(userId, appViewModel.roomListUpdateCounter) {
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

                // Device List availability check
                val encInfo = userProfileInfo!!.encryptionInfo
                val deviceCount = encInfo?.devices?.size ?: 0
                val isDeviceListAvailable = encInfo != null && encInfo.devicesTracked && deviceCount > 0

                // Shared Rooms availability check
                val sharedRoomsCount = userProfileInfo!!.mutualRooms.size
                val isSharedRoomsAvailable = sharedRoomsCount > 0

                var isCreatingDm by remember { mutableStateOf(false) }

                // 2x2 grid: row 1 always shown, row 2 only for own profile
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                if (isDmAvailable) {
                                    val encodedRoomId = java.net.URLEncoder.encode(joinedDmRoomId, "UTF-8")
                                    navController.navigate("room_timeline/$encodedRoomId")
                                } else {
                                    isCreatingDm = true
                                    appViewModel.createRoom(
                                        name = null,
                                        topic = null,
                                        roomAliasName = null,
                                        preset = "trusted_private_chat",
                                        isDirect = true,
                                        invite = listOf(userId),
                                        initialState = listOf(
                                            mapOf(
                                                "type" to "m.room.encryption",
                                                "content" to mapOf("algorithm" to "m.megolm.v1.aes-sha2"),
                                            ),
                                        ),
                                    ) { newRoomId, error ->
                                        coroutineScope.launch(Dispatchers.Main) {
                                            isCreatingDm = false
                                            if (newRoomId != null) {
                                                val encodedRoomId = java.net.URLEncoder.encode(newRoomId, "UTF-8")
                                                navController.navigate("room_timeline/$encodedRoomId")
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Failed to create DM: $error",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isCreatingDm,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            if (isCreatingDm) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(
                                    text = if (isDmAvailable) "Go to\nDM" else "Create\nDM",
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (encInfo != null && !encInfo.devicesTracked) {
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
                            enabled = isDeviceListAvailable || (encInfo != null && !encInfo.devicesTracked),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            val buttonText = when {
                                encInfo == null -> "Device List"
                                !encInfo.devicesTracked -> "Track\nDevices"
                                else -> "Device List"
                            }
                            Text(text = buttonText, textAlign = TextAlign.Center)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { showSharedRoomsDialog = true },
                            enabled = isSharedRoomsAvailable,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(text = "Shared\nRooms", textAlign = TextAlign.Center)
                        }

                        Button(
                            onClick = { navController.navigate("per_message_profile_editor") },
                            enabled = myUserId == userId,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(text = "Per-Message\nProfiles", textAlign = TextAlign.Center)
                        }
                    }
                }

                // Add to Contacts Button removed - functionality moved to save button in TopAppBar

                // Moderation buttons (only shown if we have a room context and not viewing own profile)
                if (effectiveRoomId != null && myUserId != userId) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // 2x2 grid of moderation buttons
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // First row: Kick and Ban
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Kick button
                            Button(
                                onClick = {
                                    kickReason = ""
                                    showKickDialog = true
                                },
                                enabled = canKick,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Kick")
                            }

                            // Ban button
                            Button(
                                onClick = {
                                    banReason = ""
                                    banRedactRecentMessages = false // OFF by default
                                    banRedactSystemMessages = true
                                    showBanDialog = true
                                },
                                enabled = canBan,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Ban")
                            }
                        }

                        // Second row: Redact and Ignore
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Redact button
                            Button(
                                onClick = {
                                    redactReason = ""
                                    showRedactDialog = true
                                },
                                enabled = canRedact,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Redact")
                            }

                            // Ignore/Unignore button
                            Button(
                                onClick = {
                                    showIgnoreDialog = true
                                },
                                enabled = true,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (isUserIgnored) "Unignore" else "Ignore")
                            }
                        }
                    }
                }

                // Arbitrary profile fields (exclude known/handled keys)
                val hiddenKnownProfileKeys = setOf(
                    "m.status",
                    "m.call",
                    "org.msc.4426.status",
                    "org.msc.4426.call",
                    "org.msc4426.status",
                    "org.msc4426.call",
                    "org.msc4266.status",
                    "org.msc4266.call",
                    "m.tz",
                    "us.cloke.msc4175.tz",
                    "io.fsky.nyx.pronouns",
                    "chat.commet.profile_banner",
                    "chat.commet.profile_bio",
                    "moe.sable.app.bio",
                    "m.per_message_profiles",
                    "fi.mau.msc4461.per_message_profiles",
                    "fi.mau.msc4461.per_message_profiles.v2",
                    "fi.mau.msc4461.per_message_profiles.v3",
                )
                val arbitraryFields = userProfileInfo!!.arbitraryFields
                    .filterKeys { it !in hiddenKnownProfileKeys }
                if (arbitraryFields.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Additional Profile Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp, start = 16.dp, end = 16.dp),
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            onDismiss = { showDeviceListDialog = false },
        )
    }

    if (showFullAvatarDialog && fullAvatarMxc != null) {
        ImageViewerDialog(
            mediaMessage = avatarImageMediaMessage(fullAvatarMxc!!),
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            isEncrypted = false,
            onDismiss = { showFullAvatarDialog = false },
        )
    }

    if (showStatusEditDialog) {
        AlertDialog(
            onDismissRequest = { showStatusEditDialog = false },
            title = { Text("Set Status") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 1.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(56.dp)
                                .clickable {
                                    statusEditError = null
                                    showStatusEmojiPicker = true
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = statusEmojiInput.ifBlank { "🙂" },
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = statusTextInput,
                            onValueChange = { statusTextInput = it },
                            label = { Text("Status text") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (statusEditError != null) {
                        Text(
                            text = statusEditError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val emoji = statusEmojiInput.trim()
                        val text = statusTextInput
                        when {
                            !isSingleGrapheme(emoji) -> statusEditError = "Please enter exactly one emoji."

                            text.isBlank() -> statusEditError = "Status text cannot be empty."

                            text.toByteArray(
                                Charsets.UTF_8,
                            ).size > 256 -> statusEditError = "Status text is too long (max 256 bytes)."

                            emoji.toByteArray(
                                Charsets.UTF_8,
                            ).size > 32 -> statusEditError = "Emoji is too long (max 32 bytes)."

                            else -> {
                                statusEditError = null
                                val payload = mapOf(
                                    "text" to text,
                                    "emoji" to emoji,
                                )
                                appViewModel.setCustomProfileField("m.status", payload)
                                appViewModel.setCustomProfileField("org.msc.4426.status", payload)
                                val updatedFields = userProfileInfo?.arbitraryFields?.toMutableMap() ?: mutableMapOf()
                                val statusJson = JSONObject().apply {
                                    put("text", text)
                                    put("emoji", emoji)
                                }
                                updatedFields["m.status"] = statusJson
                                updatedFields["org.msc.4426.status"] = statusJson
                                userProfileInfo = userProfileInfo?.copy(arbitraryFields = updatedFields)
                                showStatusEditDialog = false
                            }
                        }
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStatusEditDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showStatusEmojiPicker) {
        EmojiSelectionDialog(
            recentEmojis = appViewModel.recentEmojis,
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            onEmojiSelected = { selected ->
                if (selected.startsWith("mxc://") || selected.startsWith("![:")) {
                    statusEditError = "Please choose a Unicode emoji."
                } else {
                    statusEmojiInput = selected
                    statusEditError = null
                }
            },
            onDismiss = { showStatusEmojiPicker = false },
            customEmojiPacks = emptyList(),
            allowCustomReactions = false,
        )
    }

    if (showAddProfileInfoDialog) {
        val profile = userProfileInfo
        val existingStatus = profile?.let { extractProfileStatus(it.arbitraryFields) }
        val existingPronouns = profile?.pronouns
        val existingTimezone = profile?.timezone
        val existingBanner = profile?.let { extractProfileBanner(it.arbitraryFields) }
        val existingBios = profile?.let { extractProfileBios(it.arbitraryFields) } ?: emptyList()
        val hasCommetBio = existingBios.any { it.sourceKey == "chat.commet.profile_bio" }
        val missingStatus = existingStatus == null
        val missingPronouns = existingPronouns.isNullOrEmpty()
        val missingTimezone = existingTimezone.isNullOrBlank()
        val missingBanner = existingBanner == null
        val missingSpecBio = profile?.bio == null
        val missingBio = !hasCommetBio
        val allSet = !missingStatus && !missingPronouns && !missingTimezone && !missingBanner &&
            !missingBio && !missingSpecBio
        AlertDialog(
            onDismissRequest = { showAddProfileInfoDialog = false },
            title = { Text("Add Profile Info") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (allSet) {
                        Text("All supported profile fields are already set.")
                    }
                    if (missingStatus) {
                        TextButton(
                            onClick = {
                                statusEmojiInput = ""
                                statusTextInput = ""
                                statusEditError = null
                                showAddProfileInfoDialog = false
                                showStatusEditDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Status (m.status, org.msc4266.status)")
                        }
                    }
                    if (missingTimezone) {
                        TextButton(
                            onClick = {
                                timezoneInput = TimeZone.getDefault().id
                                showAddProfileInfoDialog = false
                                showTimezoneEditDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Timezone")
                        }
                    }
                    if (missingPronouns) {
                        TextButton(
                            onClick = {
                                pronounsLanguageInput = "en"
                                pronounsSummaryInput = ""
                                pronounsEditError = null
                                showAddProfileInfoDialog = false
                                showPronounsEditDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Pronouns")
                        }
                    }
                    if (missingBanner) {
                        TextButton(
                            onClick = {
                                bannerUploadError = null
                                showAddProfileInfoDialog = false
                                showBannerEditDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Profile Banner")
                        }
                    }
                    if (missingSpecBio) {
                        TextButton(
                            onClick = {
                                bioInput = ""
                                bioEditTarget = SPEC_BIO_SOURCE_KEY
                                bioEditError = null
                                showAddProfileInfoDialog = false
                                showBioEditDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Bio (MSC4440)")
                        }
                    }
                    if (missingBio) {
                        TextButton(
                            onClick = {
                                bioInput = ""
                                bioEditTarget = "chat.commet.profile_bio"
                                bioEditError = null
                                showAddProfileInfoDialog = false
                                showBioEditDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Profile Bio (chat.commet)")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddProfileInfoDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showPronounsEditDialog) {
        AlertDialog(
            onDismissRequest = { showPronounsEditDialog = false },
            title = { Text("Edit Pronouns") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pronounsLanguageInput,
                        onValueChange = { pronounsLanguageInput = it },
                        label = { Text("Language") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pronounsSummaryInput,
                        onValueChange = { pronounsSummaryInput = it },
                        label = { Text("Pronouns") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pronounsEditError != null) {
                        Text(
                            text = pronounsEditError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val language = pronounsLanguageInput.trim().ifBlank { "en" }
                        val summary = pronounsSummaryInput.trim()
                        if (summary.isBlank()) {
                            pronounsEditError = "Pronouns cannot be empty."
                            return@TextButton
                        }
                        val pronounsPayload = listOf(
                            mapOf(
                                "language" to language,
                                "summary" to summary,
                            ),
                        )
                        appViewModel.setCustomProfileField("io.fsky.nyx.pronouns", pronounsPayload)
                        val updatedFields = userProfileInfo?.arbitraryFields?.toMutableMap() ?: mutableMapOf()
                        val pronounsArray = JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("language", language)
                                    put("summary", summary)
                                },
                            )
                        }
                        updatedFields["io.fsky.nyx.pronouns"] = pronounsArray
                        userProfileInfo = userProfileInfo?.copy(
                            pronouns = listOf(UserPronouns(language = language, summary = summary)),
                            arbitraryFields = updatedFields,
                        )
                        pronounsEditError = null
                        showPronounsEditDialog = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPronounsEditDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showTimezoneEditDialog) {
        var timezoneDropdownExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showTimezoneEditDialog = false },
            title = { Text("Edit Timezone") },
            text = {
                ExposedDropdownMenuBox(
                    expanded = timezoneDropdownExpanded,
                    onExpandedChange = { timezoneDropdownExpanded = !timezoneDropdownExpanded },
                ) {
                    OutlinedTextField(
                        value = timezoneInput,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Timezone") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = timezoneDropdownExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = timezoneDropdownExpanded,
                        onDismissRequest = { timezoneDropdownExpanded = false },
                    ) {
                        allTimezones.forEach { tz ->
                            DropdownMenuItem(
                                text = { Text(tz) },
                                onClick = {
                                    timezoneInput = tz
                                    timezoneDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (timezoneInput.isBlank()) return@TextButton
                        appViewModel.setCustomProfileField("m.tz", timezoneInput)
                        appViewModel.setCustomProfileField("us.cloke.msc4175.tz", timezoneInput)
                        val updatedFields = userProfileInfo?.arbitraryFields?.toMutableMap() ?: mutableMapOf()
                        updatedFields["m.tz"] = timezoneInput
                        updatedFields["us.cloke.msc4175.tz"] = timezoneInput
                        userProfileInfo = userProfileInfo?.copy(
                            timezone = timezoneInput,
                            arbitraryFields = updatedFields,
                        )
                        showTimezoneEditDialog = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimezoneEditDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Banner Edit Dialog
    if (showBannerEditDialog) {
        val bannerImagePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            uri?.let { selectedUri ->
                val mimeType = context.contentResolver.getType(selectedUri)
                if (mimeType?.startsWith("image/") == true) {
                    bannerUploadInProgress = true
                    bannerUploadError = null
                    coroutineScope.launch {
                        try {
                            val uploadResult = MediaUploadUtils.uploadMedia(
                                context = context,
                                uri = selectedUri,
                                homeserverUrl = appViewModel.homeserverUrl,
                                authToken = appViewModel.authToken,
                                isEncrypted = false,
                                compressOriginal = false,
                            )
                            if (uploadResult != null) {
                                appViewModel.setCustomProfileField("chat.commet.profile_banner", uploadResult.mxcUrl)
                                val updatedFields = userProfileInfo?.arbitraryFields?.toMutableMap() ?: mutableMapOf()
                                updatedFields["chat.commet.profile_banner"] = uploadResult.mxcUrl
                                userProfileInfo = userProfileInfo?.copy(arbitraryFields = updatedFields)
                                bannerUploadInProgress = false
                                showBannerEditDialog = false
                                Toast.makeText(context, "Banner updated", Toast.LENGTH_SHORT).show()
                            } else {
                                bannerUploadError = "Failed to upload banner image"
                                bannerUploadInProgress = false
                            }
                        } catch (e: Exception) {
                            Log.e("Andromuks", "UserInfo: Banner upload error", e)
                            bannerUploadError = "Error: ${e.message}"
                            bannerUploadInProgress = false
                        }
                    }
                } else {
                    bannerUploadError = "Please select an image file"
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (!bannerUploadInProgress) showBannerEditDialog = false
            },
            title = { Text("Set Profile Banner") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Select an image to use as your profile banner. The banner will be displayed behind your avatar on your profile.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (bannerUploadInProgress) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text("Uploading banner...")
                        }
                    }
                    if (bannerUploadError != null) {
                        Text(
                            text = bannerUploadError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { bannerImagePickerLauncher.launch("image/*") },
                    enabled = !bannerUploadInProgress,
                ) {
                    Text("Choose Image")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBannerEditDialog = false },
                    enabled = !bannerUploadInProgress,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    // Full-size bio viewer
    expandedBio?.let { bio ->
        ExpandedBioDialog(
            profileBio = bio,
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            onMatrixUserClick = { clickedUserId ->
                expandedBio = null
                navController.navigateToUserInfo(clickedUserId, roomId)
            },
            onDismiss = { expandedBio = null },
        )
    }

    // Bio Edit Dialog
    if (showBioEditDialog) {
        AlertDialog(
            onDismissRequest = { showBioEditDialog = false },
            title = { Text("Edit Profile Bio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Write your bio using Markdown formatting:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "**bold**, *italic*, > quote, [link](url)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = bioInput,
                        onValueChange = { bioInput = it },
                        label = { Text("Bio") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        maxLines = 10,
                    )
                    if (bioEditError != null) {
                        Text(
                            text = bioEditError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val bio = bioInput.trim()
                        if (bio.isBlank()) {
                            bioEditError = "Bio cannot be empty."
                            return@TextButton
                        }
                        if (bio.toByteArray(Charsets.UTF_8).size > 4096) {
                            bioEditError = "Bio is too long (max 4KB)."
                            return@TextButton
                        }
                        val htmlBody = markdownToHtml(bio)
                        if (bioEditTarget == SPEC_BIO_SOURCE_KEY) {
                            // gomuks' "_gomuks_bio" write alias takes the raw input and does the
                            // MSC1767 conversion itself, so no payload is built here.
                            appViewModel.setCustomProfileField("_gomuks_bio", bio)
                            // Local preview only, replaced by the backend's rendering on the next
                            // fetch. Honour gomuks' "/html " input prefix, which means the rest is
                            // already HTML and must not go through the markdown converter.
                            val previewHtml = bio.removePrefix(GOMUKS_HTML_INPUT_PREFIX)
                                .takeIf { it != bio }
                                ?: htmlBody
                            userProfileInfo = userProfileInfo?.copy(
                                bio = ProfileBioContent(html = previewHtml, editSource = bio),
                            )
                        } else {
                            val bioPayload = mapOf(
                                "body" to bio,
                                "format" to "org.matrix.custom.html",
                                "formatted_body" to htmlBody,
                            )
                            appViewModel.setCustomProfileField("chat.commet.profile_bio", bioPayload)
                            val updatedFields = userProfileInfo?.arbitraryFields?.toMutableMap() ?: mutableMapOf()
                            val bioJson = JSONObject().apply {
                                put("body", bio)
                                put("format", "org.matrix.custom.html")
                                put("formatted_body", htmlBody)
                            }
                            updatedFields["chat.commet.profile_bio"] = bioJson
                            userProfileInfo = userProfileInfo?.copy(arbitraryFields = updatedFields)
                        }
                        bioEditError = null
                        showBioEditDialog = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (bioEditTarget == SPEC_BIO_SOURCE_KEY && userProfileInfo?.bio != null) {
                        TextButton(
                            onClick = {
                                // Delete the real field, not the "_gomuks_bio" write alias.
                                appViewModel.clearCustomProfileField(SPEC_BIO_SOURCE_KEY)
                                userProfileInfo = userProfileInfo?.copy(bio = null)
                                bioEditError = null
                                showBioEditDialog = false
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Clear")
                        }
                    }
                    TextButton(onClick = { showBioEditDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    // Shared Rooms Dialog
    if (showSharedRoomsDialog && userProfileInfo != null) {
        SharedRoomsDialog(
            mutualRooms = userProfileInfo!!.mutualRooms,
            appViewModel = appViewModel,
            navController = navController,
            onDismiss = { showSharedRoomsDialog = false },
        )
    }

    // Kick confirmation dialog
    if (showKickDialog && effectiveRoomId != null) {
        val displayName = userProfileInfo?.roomDisplayName ?: userProfileInfo?.displayName ?: usernameFromMatrixId(
            userId,
        )
        KickConfirmationDialog(
            displayName = displayName,
            userId = userId,
            reason = kickReason,
            onReasonChange = { kickReason = it },
            onConfirm = {
                // Execute kick command (same as /kick command)
                val commandText = if (kickReason.isNotBlank()) {
                    "/kick $userId $kickReason"
                } else {
                    "/kick $userId"
                }
                appViewModel.executeCommand(effectiveRoomId, commandText, context)
                showKickDialog = false
                kickReason = ""
            },
            onDismiss = {
                showKickDialog = false
                kickReason = ""
            },
        )
    }

    // Ban confirmation dialog
    if (showBanDialog && effectiveRoomId != null) {
        val displayName = userProfileInfo?.roomDisplayName ?: userProfileInfo?.displayName ?: usernameFromMatrixId(
            userId,
        )
        // Get user messages count for the room (only message events, not state events)
        val userMessages = remember(effectiveRoomId, userId) {
            RoomTimelineCache.getCachedEvents(effectiveRoomId).orEmpty()
                .filter {
                    it.sender == userId &&
                        it.stateKey == null &&
                        // Exclude state events
                        (
                            it.type == "m.room.message" ||
                                (it.type == "m.room.encrypted" && it.decryptedType == "m.room.message")
                            )
                }
        }
        val messageCount = userMessages.size

        // Get all user events (including state events) for system message redaction
        val allUserEvents = remember(effectiveRoomId, userId) {
            RoomTimelineCache.getCachedEvents(effectiveRoomId).orEmpty()
                .filter { it.sender == userId }
        }

        BanConfirmationDialog(
            displayName = displayName,
            userId = userId,
            messageCount = messageCount,
            reason = banReason,
            onReasonChange = { banReason = it },
            redactRecentMessages = banRedactRecentMessages,
            onRedactRecentMessagesChange = { banRedactRecentMessages = it },
            redactSystemMessages = banRedactSystemMessages,
            onRedactSystemMessagesChange = { banRedactSystemMessages = it },
            onConfirm = {
                coroutineScope.launch {
                    // Execute ban command
                    // msc4293_redact_events is true if "Redact n recent messages" is ON
                    appViewModel.banUser(effectiveRoomId, userId, banReason, banRedactRecentMessages)

                    // If redact recent messages is enabled, redact all user message events (NOT state events)
                    if (banRedactRecentMessages) {
                        userMessages.forEach { event ->
                            appViewModel.redactEvent(effectiveRoomId, event.eventId, banReason)
                            // Small delay to avoid overwhelming the backend
                            delay(50)
                        }
                    }

                    // If redact system messages is enabled, redact all user events (including state events)
                    if (banRedactSystemMessages) {
                        allUserEvents.forEach { event ->
                            appViewModel.redactEvent(effectiveRoomId, event.eventId, banReason)
                            // Small delay to avoid overwhelming the backend
                            delay(50)
                        }
                    }

                    showBanDialog = false
                    banReason = ""
                    banRedactRecentMessages = false
                    banRedactSystemMessages = true
                }
            },
            onDismiss = {
                showBanDialog = false
                banReason = ""
                banRedactRecentMessages = false
                banRedactSystemMessages = true
            },
        )
    }

    // Redact confirmation dialog
    if (showRedactDialog && effectiveRoomId != null) {
        val displayName = userProfileInfo?.roomDisplayName ?: userProfileInfo?.displayName ?: usernameFromMatrixId(
            userId,
        )
        // Get user messages count for the room
        val userMessages = remember(effectiveRoomId, userId) {
            RoomTimelineCache.getCachedEvents(effectiveRoomId).orEmpty()
                .filter {
                    it.sender == userId &&
                        (
                            it.type == "m.room.message" ||
                                (it.type == "m.room.encrypted" && it.decryptedType == "m.room.message")
                            )
                }
        }
        val messageCount = userMessages.size

        RedactConfirmationDialog(
            displayName = displayName,
            userId = userId,
            messageCount = messageCount,
            reason = redactReason,
            onReasonChange = { redactReason = it },
            onConfirm = {
                coroutineScope.launch {
                    // Redact all user messages
                    userMessages.forEach { event ->
                        appViewModel.redactEvent(effectiveRoomId, event.eventId, redactReason)
                        // Small delay to avoid overwhelming the backend
                        delay(50)
                    }

                    showRedactDialog = false
                    redactReason = ""
                }
            },
            onDismiss = {
                showRedactDialog = false
                redactReason = ""
            },
        )
    }

    // Ignore confirmation dialog
    val currentUserProfileInfo = userProfileInfo
    if (showIgnoreDialog && currentUserProfileInfo != null) {
        val displayName =
            currentUserProfileInfo.roomDisplayName ?: currentUserProfileInfo.displayName ?: usernameFromMatrixId(
                userId,
            )
        IgnoreConfirmationDialog(
            displayName = displayName,
            userId = userId,
            isIgnored = isUserIgnored,
            onConfirm = {
                appViewModel.setIgnoredUser(userId, !isUserIgnored)
                isUserIgnored = !isUserIgnored
                showIgnoreDialog = false
            },
            onDismiss = {
                showIgnoreDialog = false
            },
        )
    }
}

/**
 * The full bio, in a floating window over the profile.
 *
 * The card on the profile screen is deliberately short, which squeezes bios that carry images —
 * inline `<img>` there are clamped to one line of text so emoticons sit on the baseline. Here the
 * markup gets the room it expects: images render at their declared size, bounded by the dialog's
 * own width and a share of the screen height, and the body scrolls.
 */
@Composable
private fun ExpandedBioDialog(profileBio: ProfileBio, homeserverUrl: String, authToken: String, onMatrixUserClick: (String) -> Unit, onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    // A tall image would otherwise be able to fill the window on its own, leaving no sign that
    // there is text around it.
    val maxImageHeight = (configuration.screenHeightDp * 0.5f).dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = (configuration.screenHeightDp * 0.85f).dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = bioLabelFor(profileBio.sourceKey),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close bio",
                        )
                    }
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(end = 12.dp),
                ) {
                    // maxWidth is the real content width here, so images are bounded by the
                    // window rather than by a guess at the screen size.
                    ProfileBioBody(
                        profileBio = profileBio,
                        contentWidth = maxWidth,
                        maxImageHeight = maxImageHeight,
                        color = MaterialTheme.colorScheme.onSurface,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onMatrixUserClick = onMatrixUserClick,
                    )
                }
            }
        }
    }
}

/**
 * A profile bio's body, rendered the same way in the card and in the expanded window — only the
 * bounds and the text colour differ.
 *
 * Real images are laid out as blocks rather than as inline text content, which cannot be taller
 * than the line it sits on (see
 * [HTML_RENDERING.md](../../../../../../docs/HTML_RENDERING.md#why-a-real-image-cannot-be-inline-content)).
 * Custom emoticons carry no declared size, so they stay inside the markup runs, inline, where they
 * belong. [contentWidth] must be the caller's measured width: it is what images are fitted to.
 */
@Composable
private fun ProfileBioBody(
    profileBio: ProfileBio,
    contentWidth: Dp,
    maxImageHeight: Dp,
    color: Color,
    homeserverUrl: String,
    authToken: String,
    onMatrixUserClick: (String) -> Unit,
) {
    if (!profileBio.isHtml) {
        Text(text = profileBio.body, style = MaterialTheme.typography.bodyMedium, color = color)
        return
    }
    val density = LocalDensity.current
    // Bounds for images left inline (nested ones, and anything a client stored with its own size
    // attributes). Placeholders are measured in sp, so the caller's Dp bounds convert here.
    val sizing = remember(contentWidth, maxImageHeight, density) {
        InlineImageSizing(
            maxHeightSp = with(density) { maxImageHeight.toSp().value },
            maxWidthSp = with(density) { contentWidth.toSp().value },
        )
    }
    val segments = remember(profileBio.body) { splitTopLevelBlockImages(profileBio.body) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is HtmlSegment.Markup -> HtmlBodyText(
                    html = segment.html,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    color = color,
                    onMatrixUserClick = onMatrixUserClick,
                    inlineImageSizing = sizing,
                )

                is HtmlSegment.BlockImage -> {
                    val (imageWidth, imageHeight) = blockImageSize(
                        image = segment,
                        maxWidth = contentWidth,
                        maxHeight = maxImageHeight,
                    )
                    BlockHtmlImage(
                        image = segment,
                        width = imageWidth,
                        height = imageHeight,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                    )
                }
            }
        }
    }
}

/**
 * Dialog to display shared rooms list
 */
@Composable
fun SharedRoomsDialog(mutualRooms: List<String>, appViewModel: AppViewModel, navController: NavController, onDismiss: () -> Unit) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(mutualRooms) { roomId ->
                        SharedRoomItem(
                            roomId = roomId,
                            appViewModel = appViewModel,
                            navController = navController,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

/**
 * Composable for a single shared room item
 * Shows up to 3 lines: display name, canonical alias (if available), room ID
 */
@Composable
fun SharedRoomItem(roomId: String, appViewModel: AppViewModel, navController: NavController) {
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
        verticalAlignment = Alignment.Top,
    ) {
        AvatarImage(
            mxcUrl = room?.avatarUrl,
            homeserverUrl = appViewModel.homeserverUrl,
            authToken = appViewModel.authToken,
            fallbackText = room?.name ?: roomId,
            size = 40.dp,
            userId = roomId,
            displayName = room?.name,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 0.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            // Line 1: Room display name (aligned with avatar top)
            Text(
                text = room?.name ?: roomId,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Line 2: Canonical alias (if available and room has a name)
            if (canonicalAlias != null && room?.name != null) {
                Text(
                    text = canonicalAlias,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Line 3: Room ID (always shown if we have a room name)
            if (room?.name != null) {
                Text(
                    text = roomId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Dialog to display device list and encryption info
 */
@Composable
fun DeviceListDialog(encryptionInfo: UserEncryptionInfo, userId: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Encryption Info")
                Text(
                    text = userId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Master key info
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Master Key Info",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )

                            if (!encryptionInfo.masterKey.isNullOrBlank()) {
                                Text(
                                    text = "Master Key:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = encryptionInfo.masterKey,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                )
                            }

                            if (!encryptionInfo.firstMasterKey.isNullOrBlank() &&
                                encryptionInfo.firstMasterKey != encryptionInfo.masterKey
                            ) {
                                Text(
                                    text = "First Master Key:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Text(
                                    text = encryptionInfo.firstMasterKey,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                )
                            }

                            Text(
                                text = "User Trusted: ${if (encryptionInfo.userTrusted) "Yes" else "No"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (encryptionInfo.userTrusted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.padding(top = 8.dp),
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
                        fontWeight = FontWeight.Bold,
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
        },
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
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Device name and ID
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "ID: ${device.deviceId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Trust state badge
            Surface(
                color = when (device.trustState) {
                    "verified" -> MaterialTheme.colorScheme.primaryContainer
                    "cross-signed-tofu" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                },
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(
                    text = device.trustState,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Fingerprint
            Text(
                text = "Fingerprint:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = device.fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )

            // Identity Key
            Text(
                text = "Identity Key:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = device.identityKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Signing Key
            Text(
                text = "Signing Key:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = device.signingKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Kick confirmation dialog
 */
@Composable
fun KickConfirmationDialog(
    displayName: String,
    userId: String,
    reason: String,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kick User") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Are you sure you want to kick $displayName ($userId)?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Reason (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("No")
            }
        },
    )
}

/**
 * Ban confirmation dialog
 */
@Composable
fun BanConfirmationDialog(
    displayName: String,
    userId: String,
    messageCount: Int,
    reason: String,
    onReasonChange: (String) -> Unit,
    redactRecentMessages: Boolean,
    onRedactRecentMessagesChange: (Boolean) -> Unit,
    redactSystemMessages: Boolean,
    onRedactSystemMessagesChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ban User") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Are you sure you want to ban $displayName ($userId)?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Reason (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Redact $messageCount recent messages",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        color = if (redactRecentMessages) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = 0.38f,
                            )
                        },
                    )
                    Switch(
                        checked = redactRecentMessages,
                        onCheckedChange = onRedactRecentMessagesChange,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Redact system messages",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        color = if (redactRecentMessages) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = 0.38f,
                            )
                        },
                    )
                    Switch(
                        checked = redactSystemMessages,
                        onCheckedChange = onRedactSystemMessagesChange,
                        enabled = redactRecentMessages,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("No")
            }
        },
    )
}

/**
 * Redact confirmation dialog
 */
@Composable
fun RedactConfirmationDialog(
    displayName: String,
    userId: String,
    messageCount: Int,
    reason: String,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Redact Messages") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Do you want to redact $messageCount messages for $displayName ($userId)?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Reason (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("No")
            }
        },
    )
}

/**
 * Ignore/Unignore confirmation dialog
 */
@Composable
fun IgnoreConfirmationDialog(displayName: String, userId: String, isIgnored: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isIgnored) "Unignore User" else "Ignore User") },
        text = {
            Text(
                text = if (isIgnored) {
                    "Are you sure you want to unignore $displayName ($userId)?"
                } else {
                    "Are you sure you want to ignore $displayName ($userId)?"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Yes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("No")
            }
        },
    )
}

suspend fun addMatrixUserToContacts(context: Context, userId: String, displayName: String, avatarUrl: String?, homeserverUrl: String, authToken: String) =
    withContext(Dispatchers.IO) {
        val hasRead = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        val hasWrite = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasRead || !hasWrite) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Contacts permissions required to add contact", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        val (accountName, accountType) = getDefaultContactAccount(context)
        if (accountName == null || accountType == null) {
            Log.e("UserInfo", "No contact account available")
            return@withContext
        }

        val syncService = ContactsSyncService(
            context,
            accountName = "Andromuks", // display name for the account
            accountType = "net.vrkknn.andromuks.matrix",
        )
        val user = net.vrkknn.andromuks.MatrixUser(
            userId = userId,
            displayName = displayName,
            avatarUrl = avatarUrl,
        )
        syncService.syncContacts(listOf(user), syncAvatars = avatarUrl != null)
    }

/**
 * Convert basic Markdown to HTML for profile bio.
 * Supports: **bold**, *italic*, > blockquote, [text](url), `code`, and newlines.
 */
private fun markdownToHtml(markdown: String): String {
    var html = markdown

    // Escape HTML special characters first (except in URLs which we handle separately)
    html = html
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // Restore blockquote markers (they were escaped as &gt;)
    html = html.replace(Regex("^&gt;\\s*", RegexOption.MULTILINE), "> ")

    // Process blockquotes (lines starting with >)
    val lines = html.split("\n")
    val processedLines = mutableListOf<String>()
    var inBlockquote = false

    for (line in lines) {
        if (line.startsWith("> ")) {
            if (!inBlockquote) {
                processedLines.add("<blockquote>")
                inBlockquote = true
            }
            processedLines.add(line.removePrefix("> "))
        } else {
            if (inBlockquote) {
                processedLines.add("</blockquote>")
                inBlockquote = false
            }
            processedLines.add(line)
        }
    }
    if (inBlockquote) {
        processedLines.add("</blockquote>")
    }
    html = processedLines.joinToString("\n")

    // Bold: **text** or __text__
    html = html.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
    html = html.replace(Regex("__(.+?)__"), "<strong>$1</strong>")

    // Italic: *text* or _text_ (but not inside URLs or already processed bold)
    html = html.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "<em>$1</em>")
    html = html.replace(Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)"), "<em>$1</em>")

    // Inline code: `code`
    html = html.replace(Regex("`([^`]+)`"), "<code>$1</code>")

    // Links: [text](url)
    html = html.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")) { match ->
        val text = match.groupValues[1]
        val url = match.groupValues[2]
            .replace("&amp;", "&") // Restore & in URLs
        "<a href=\"$url\">$text</a>"
    }

    // Convert double newlines to paragraph breaks
    html = html.replace(Regex("\n\n+"), "</p><p>")

    // Convert single newlines to <br>
    html = html.replace("\n", "<br>")

    // Wrap in paragraph tags if not already wrapped
    if (!html.startsWith("<p>") && !html.startsWith("<blockquote>")) {
        html = "<p>$html</p>"
    }

    return html
}
