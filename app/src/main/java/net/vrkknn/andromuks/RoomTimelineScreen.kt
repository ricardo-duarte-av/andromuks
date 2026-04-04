package net.vrkknn.andromuks

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import java.io.File
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.updateTransition
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.memory.MemoryCache
import net.vrkknn.andromuks.ScrollHighlightState
import net.vrkknn.andromuks.LocalScrollHighlightState
import net.vrkknn.andromuks.ui.components.AvatarImage
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.key
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import net.vrkknn.andromuks.ui.components.BridgeNetworkBadge
import net.vrkknn.andromuks.ui.theme.AndromuksTheme
import net.vrkknn.andromuks.ui.components.ExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ContainedExpressiveLoadingIndicator
import net.vrkknn.andromuks.ui.components.ExpressiveStatusRow
import net.vrkknn.andromuks.utils.CustomBubbleTextField
import net.vrkknn.andromuks.utils.DeleteMessageDialog
import net.vrkknn.andromuks.utils.EditPreviewInput
import net.vrkknn.andromuks.utils.EmojiSelectionDialog
import net.vrkknn.andromuks.utils.StickerSelectionDialog
import net.vrkknn.andromuks.utils.EmoteEventNarrator
import net.vrkknn.andromuks.utils.HtmlMessageText
import net.vrkknn.andromuks.utils.CodeViewer
import net.vrkknn.andromuks.utils.InlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.AnimatedInlineReadReceiptAvatars
import net.vrkknn.andromuks.utils.navigateToUserInfo
import net.vrkknn.andromuks.utils.MediaMessage
import net.vrkknn.andromuks.utils.MediaPreviewDialog
import net.vrkknn.andromuks.utils.MediaPreviewDialogMultiple
import net.vrkknn.andromuks.utils.MediaPreviewItemSendState
import net.vrkknn.andromuks.utils.MediaUploadUtils
import net.vrkknn.andromuks.utils.MessageBubbleWithMenu
import net.vrkknn.andromuks.utils.MessageMenuBar
import net.vrkknn.andromuks.utils.MessageMenuConfig
import net.vrkknn.andromuks.utils.LocalActiveMessageMenuEventId
import net.vrkknn.andromuks.utils.MessageSoundPlayer
import net.vrkknn.andromuks.utils.ReactionBadges
import net.vrkknn.andromuks.utils.ReplyPreview
import net.vrkknn.andromuks.utils.ReplyPreviewInput
import net.vrkknn.andromuks.utils.RoomJoinerScreen
import net.vrkknn.andromuks.utils.RoomLink
import net.vrkknn.andromuks.utils.SmartMessageText
import net.vrkknn.andromuks.utils.StickerMessage
import net.vrkknn.andromuks.utils.SystemEventNarrator
import net.vrkknn.andromuks.utils.TypingNotificationArea
import net.vrkknn.andromuks.utils.UploadingDialog
import net.vrkknn.andromuks.utils.VideoUploadUtils
import net.vrkknn.andromuks.utils.extractStickerFromEvent
import net.vrkknn.andromuks.utils.supportsHtmlRendering
import net.vrkknn.andromuks.utils.EmojiShortcodes
import net.vrkknn.andromuks.utils.EmojiSuggestionList
import net.vrkknn.andromuks.utils.CommandSuggestionList
import net.vrkknn.andromuks.utils.CommandDefinition
import net.vrkknn.andromuks.utils.AvatarUtils
import net.vrkknn.andromuks.utils.ImageLoaderSingleton
import net.vrkknn.andromuks.BuildConfig

// Simple flash mode enum for camera overlay
enum class CameraFlashMode { OFF, AUTO, ON }

@Composable
private fun InAppCameraPreview(
    modifier: Modifier = Modifier,
    useFrontCamera: Boolean,
    onImageCaptureReady: (ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // Fit center keeps full 4:3 frame visible and letterboxed as needed
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        // Hint that we want a 4:3 stream to match common photo sensors
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also { p ->
                            p.setSurfaceProvider(previewView.surfaceProvider)
                        }
                    val imageCapture = ImageCapture.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                    val cameraSelector =
                        if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                        onImageCaptureReady(imageCapture)
                    } catch (e: Exception) {
                        Log.e("Andromuks", "Failed to bind CameraX preview", e)
                    }
                },
                ContextCompat.getMainExecutor(ctx)
            )

            previewView
        },
        modifier = modifier
    )
}

@Composable
private fun InAppVideoPreview(
    modifier: Modifier = Modifier,
    useFrontCamera: Boolean,
    onVideoCaptureReady: (VideoCapture<Recorder>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also { p -> p.setSurfaceProvider(previewView.surfaceProvider) }
                    val recorder = Recorder.Builder().build()
                    val videoCapture = VideoCapture.Builder(recorder).build()
                    val cameraSelector =
                        if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            videoCapture
                        )
                        onVideoCaptureReady(videoCapture)
                    } catch (e: Exception) {
                        Log.e("Andromuks", "Failed to bind CameraX video preview", e)
                    }
                },
                ContextCompat.getMainExecutor(ctx)
            )

            previewView
        },
        modifier = modifier
    )
}

private data class CameraOrientation(val surfaceRotation: Int, val iconAngle: Float)

@Composable
private fun rememberCameraOrientation(): CameraOrientation {
    val context = LocalContext.current
    var surfaceRotation by rememberSaveable { mutableStateOf(Surface.ROTATION_0) }
    var iconAngle by rememberSaveable { mutableStateOf(0f) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return

                val (newSurfaceRotation, newIconAngle) = when {
                    orientation <= 45 || orientation > 315 ->
                        Surface.ROTATION_0 to 0f              // Portrait
                    orientation in 46..135 ->
                        Surface.ROTATION_270 to -90f          // Landscape right (swap 90/270, icons rotate right)
                    orientation in 136..225 ->
                        Surface.ROTATION_180 to 180f          // Upside down
                    orientation in 226..315 ->
                        Surface.ROTATION_90 to 90f            // Landscape left (swap 90/270, icons rotate left)
                    else -> return
                }

                if (newSurfaceRotation != surfaceRotation || newIconAngle != iconAngle) {
                    surfaceRotation = newSurfaceRotation
                    iconAngle = newIconAngle
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    return CameraOrientation(surfaceRotation = surfaceRotation, iconAngle = iconAngle)
}

/** Sealed class for timeline items (events and date dividers) */
sealed class TimelineItem {
    // PERFORMANCE: Stable key for LazyColumn items
    abstract val stableKey: String
    
    data class Event(
        val event: TimelineEvent,
        val isConsecutive: Boolean = false,
        val hasPerMessageProfile: Boolean = false
    ) : TimelineItem() {
        override val stableKey: String
            get() = event.eventId
    }

    data class DateDivider(val date: String) : TimelineItem() {
        override val stableKey: String get() = "date_$date"
    }
}

/** Format timestamp to date string (dd / MM / yyyy) */
internal fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("dd / MM / yyyy", Locale.getDefault())
    return formatter.format(date)
}

/** PERFORMANCE: Helper function to process timeline events in background */
suspend fun processTimelineEvents(
    timelineEvents: List<TimelineEvent>,
    allowedEventTypes: Set<String>
): List<TimelineEvent> = withContext(Dispatchers.Default) {
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "RoomTimelineScreen: Background processing ${timelineEvents.size} timeline events"
    )

    // Debug: Log event types in timeline
    val eventTypes = timelineEvents.groupBy { it.type }
    if (BuildConfig.DEBUG) Log.d(
        "Andromuks",
        "RoomTimelineScreen: Event types in timeline: ${eventTypes.map { "${it.key}: ${it.value.size}" }.joinToString(", ")}"
    )
    // Debug: Check specifically for tombstone events
    val tombstoneEvents = timelineEvents.filter { it.type == "m.room.tombstone" }
    if (BuildConfig.DEBUG && tombstoneEvents.isNotEmpty()) {
        Log.d("Andromuks", "RoomTimelineScreen: Found ${tombstoneEvents.size} tombstone event(s): ${tombstoneEvents.map { it.eventId }}")
    }

    val filteredEvents = timelineEvents.filter { event ->
        // Filter out redaction events
        if (event.type == "m.room.redaction") return@filter false
        // Filter out org.matrix.msc4075.* events (call notifications - should be hidden)
        if (event.type.startsWith("org.matrix.msc4075.") || 
            event.decryptedType?.startsWith("org.matrix.msc4075.") == true) {
            return@filter false
        }
        // Only allow events in the whitelist
        allowedEventTypes.contains(event.type)
    }
    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: After type filtering: ${filteredEvents.size} events")
    // Debug: Check if tombstone events passed the filter
    val filteredTombstoneEvents = filteredEvents.filter { it.type == "m.room.tombstone" }
    if (BuildConfig.DEBUG && filteredTombstoneEvents.isNotEmpty()) {
        Log.d("Andromuks", "RoomTimelineScreen: ${filteredTombstoneEvents.size} tombstone event(s) passed filtering: ${filteredTombstoneEvents.map { it.eventId }}")
    }

    // PERFORMANCE: Remove edit events (m.replace) but keep the original messages in the list.
    val eventsWithoutEdits = filteredEvents.filter { event ->
        when {
            event.type == "m.room.message" -> {
                val isEditEvent = event.relationType == "m.replace" ||
                    event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                if (isEditEvent) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Filtering out edit event (m.replace) ${event.eventId}")
                }
                !isEditEvent
            }
            event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> {
                val isEditEvent = event.relationType == "m.replace" ||
                    event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.replace"
                if (isEditEvent) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Filtering out encrypted edit event ${event.eventId}")
                }
                !isEditEvent
            }
            else -> true
        }
    }

    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: After edit filtering: ${eventsWithoutEdits.size} events")

    // Sort by timeline_rowid (server order) when positive; fall back to timestamp when 0 or -1.
    val sorted = eventsWithoutEdits.sortedWith(Comparator { a, b ->
        if (a.timelineRowid > 0L && b.timelineRowid > 0L) {
            val cmp = a.timelineRowid.compareTo(b.timelineRowid)
            if (cmp != 0) return@Comparator cmp
        }
        compareValuesBy(a, b, { it.timestamp }, { it.eventId })
    })
    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Final sorted events: ${sorted.size} events")

    sorted
}

/** Floating member list for mentions */
@Composable
fun MentionMemberList(
    members: Map<String, MemberProfile>,
    query: String,
    onMemberSelect: (String, String?) -> Unit,
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier
) {
    val filteredMembers = remember(members, query) {
        members.filter { (userId, profile) ->
            val displayName = profile.displayName
            val username = userId.removePrefix("@").substringBefore(":")
            query.isBlank() || 
            displayName?.contains(query, ignoreCase = true) == true ||
            username.contains(query, ignoreCase = true) ||
            userId.contains(query, ignoreCase = true)
        }.entries.sortedBy { (userId, profile) -> 
            profile.displayName ?: userId 
        }
    }

    if (filteredMembers.isEmpty()) return

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp), // Rounder corners
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp  // Use tonalElevation for dark mode visibility
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 250.dp)
                .height(200.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
        ) {
            items(filteredMembers.size) { index ->
                val (userId, profile) = filteredMembers[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMemberSelect(userId, profile.displayName) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AvatarImage(
                        mxcUrl = profile.avatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = (profile.displayName ?: userId).take(1),
                        size = 32.dp,
                        userId = userId,
                        displayName = profile.displayName
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.displayName ?: userId.removePrefix("@"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (profile.displayName != null) {
                            Text(
                                text = userId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Floating room list for room mentions */
@Composable
fun RoomSuggestionList(
    rooms: List<Pair<RoomItem, String>>,
    query: String,
    onRoomSelect: (String, String) -> Unit, // (roomId, canonicalAlias)
    homeserverUrl: String,
    authToken: String,
    modifier: Modifier = Modifier
) {
    val filteredRooms = remember(rooms, query) {
        rooms.filter { (room, alias) ->
            query.isBlank() || 
            room.name.contains(query, ignoreCase = true) ||
            alias.contains(query, ignoreCase = true)
        }.sortedBy { it.first.name }
    }

    if (filteredRooms.isEmpty()) return

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .height(200.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
        ) {
            items(filteredRooms.size) { index ->
                val (room, alias) = filteredRooms[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRoomSelect(room.id, alias) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AvatarImage(
                        mxcUrl = room.avatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = room.name.take(1),
                        size = 32.dp,
                        userId = room.id,
                        displayName = room.name
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = room.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = alias,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Date divider component for timeline events */
@Composable
fun DateDivider(date: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Spacer(
            modifier =
                Modifier.weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        )

        Text(
            text = date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        androidx.compose.foundation.layout.Spacer(
            modifier =
                Modifier.weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
        )
    }
}

// NOTE: Keep this screen in sync with `BubbleTimelineScreen`. Any structural or data-flow changes
// should be mirrored between both implementations. Refer to `docs/BUBBLE_IMPLEMENTATION.md`.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalSharedTransitionApi::class, FlowPreview::class)
@Composable
fun RoomTimelineScreen(
    roomId: String,
    roomName: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel = viewModel(),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val messageSoundPlayer =
        remember(appContext) {
            MessageSoundPlayer(appContext)
        }
    DisposableEffect(messageSoundPlayer) {
        onDispose { messageSoundPlayer.release() }
    }
    val coroutineScope = rememberCoroutineScope()
    val sharedPreferences =
        remember(context) {
            context.getSharedPreferences("AndromuksAppPrefs", Context.MODE_PRIVATE)
        }
    val authToken =
        remember(sharedPreferences) { sharedPreferences.getString("gomuks_auth_token", "") ?: "" }
    val imageToken = appViewModel.imageAuthToken.takeIf { it.isNotBlank() } ?: authToken
    val myUserId = appViewModel.currentUserId
    val homeserverUrl = appViewModel.homeserverUrl
    //Log.d("Andromuks", "RoomTimelineScreen: appViewModel instance: $appViewModel")
    // PERFORMANCE FIX: Use timelineEvents directly instead of pre-rendered flow.
    // Pre-rendering on every sync was causing heavy CPU load with 580+ rooms.
    // Timeline is now rendered lazily when room is opened via processCachedEvents().
    val timelineEvents = appViewModel.timelineEvents
    val isLoading = appViewModel.isTimelineLoading
    val currentRoomState = appViewModel.currentRoomState
    var readinessCheckComplete by remember { mutableStateOf(false) }

    // Get the room item to check if it's a DM and get proper display name
    val roomItem = appViewModel.getRoomById(roomId)
    val isDirectMessage = roomItem?.isDirectMessage ?: false

    // For DM rooms, calculate the display name from member profiles
    // Note: isDirectMessage can only be true if roomItem is not null, so roomItem != null check is redundant
    val displayRoomName =
        if (isDirectMessage) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val otherParticipant = memberMap.keys.find { it != myUserId }
            val otherProfile = otherParticipant?.let { memberMap[it] }
            otherProfile?.displayName ?: otherParticipant ?: roomName
        } else {
            roomName
        }

    // For DM rooms, get the avatar from the other participant
    // CRITICAL FIX: Use roomItem.avatarUrl as fallback (like RoomListScreen does)
    // This ensures avatars show even if member map isn't populated yet
    // Note: isDirectMessage can only be true if roomItem is not null, so roomItem != null check is redundant
    val displayAvatarUrl =
        if (isDirectMessage) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val otherParticipant = memberMap.keys.find { it != myUserId }
            val otherProfile = otherParticipant?.let { memberMap[it] }
            // Use member profile avatar, fallback to roomItem avatar, then room state avatar
            otherProfile?.avatarUrl ?: roomItem.avatarUrl ?: appViewModel.currentRoomState?.avatarUrl
        } else {
            // For group rooms, use roomItem avatar as fallback (like RoomListScreen)
            roomItem?.avatarUrl ?: appViewModel.currentRoomState?.avatarUrl
        }

    // Permission to send messages based on power levels
    val canSendMessage = remember(currentRoomState, myUserId) {
        val pl = currentRoomState?.powerLevels ?: return@remember true
        val me = myUserId
        if (me.isNullOrBlank()) return@remember true
        val myPl = pl.users[me] ?: pl.usersDefault
        val required = pl.events["m.room.message"] ?: pl.eventsDefault
        myPl >= required
    }
    
    // Track batch processing state (Catching up)
    val isProcessingBatch by appViewModel.isProcessingSyncBatch.collectAsState()
    val processingBatchSize by appViewModel.processingBatchSize.collectAsState()

    // Messages typed while the WebSocket is down are buffered and sent on reconnect,
    // so we no longer gate the input on connectivity — only on permission and batch-catch-up.
    val isInputEnabled = canSendMessage && !isProcessingBatch

    // Log timeline events count only when it actually changes (not on every recomposition)
    // This prevents excessive logging during scroll
    // Use remember to track previous values and only log when they actually change
    var previousSize by remember { mutableStateOf(-1) }
    var previousIsLoading by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(timelineEvents.size, isLoading) {
        val currentSize = timelineEvents.size
        val currentIsLoading = isLoading
        if (currentSize != previousSize || currentIsLoading != previousIsLoading) {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Timeline events count: $currentSize, isLoading: $currentIsLoading"
            )
            previousSize = currentSize
            previousIsLoading = currentIsLoading
        }
    }

    // Reply state
    var replyingToEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Edit state
    var editingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Delete state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

    // Emoji selection state
    var showEmojiSelection by remember { mutableStateOf(false) }
    var reactingToEvent by remember { mutableStateOf<TimelineEvent?>(null) }
    
    // Emoji selection state for text input
    var showEmojiPickerForText by remember { mutableStateOf(false) }
    
    // Sticker selection state for text input
    var showStickerPickerForText by remember { mutableStateOf(false) }
    
    // Code viewer state
    var showCodeViewer by remember { mutableStateOf(false) }
    var codeViewerContent by remember { mutableStateOf("") }

    // Scroll highlight state for jump-to-message interactions
    var highlightedEventId by remember(roomId) { mutableStateOf<String?>(null) }
    var highlightRequestId by remember(roomId) { mutableStateOf(0) }
    var pendingNotificationJumpEventId by remember(roomId) {
        val consumed = appViewModel.consumePendingHighlightEvent(roomId)
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "RoomTimelineScreen: remember(roomId=$roomId) pendingNotificationJumpEventId = ${consumed ?: "NULL"}"
        )
        mutableStateOf(consumed)
    }

    // CRITICAL FIX: Handle notification tap while this room is already open.
    // When the user is on RoomTimelineScreen and taps a notification:
    //   - RoomListScreen is NOT composed, so its reactive LaunchedEffect can't fire.
    //   - onNewIntent → setDirectRoomNavigation stores the highlight event and calls
    //     navigateToRoomWithCache (which rebuilds the timeline), but nothing updates
    //     pendingNotificationJumpEventId (set to null by remember(roomId) on first composition).
    // This LaunchedEffect watches for new navigation triggers and handles two cases:
    //   1. Same room: consume the pending highlight event → scroll to it.
    //   2. Different room: pop back to room_list where the reactive handler takes over.
    val navTrigger = appViewModel.directRoomNavigationTrigger
    LaunchedEffect(navTrigger) {
        if (navTrigger == 0) return@LaunchedEffect // Skip initial composition
        val targetRoomId = appViewModel.getDirectRoomNavigation()
        if (targetRoomId != null) {
            if (targetRoomId == roomId) {
                // Same room — consume highlight event and clear the navigation request
                appViewModel.clearDirectRoomNavigation()
                val eventId = appViewModel.consumePendingHighlightEvent(roomId)
                if (eventId != null) {
                    if (BuildConfig.DEBUG) Log.d(
                        "Andromuks",
                        "RoomTimelineScreen: Notification tap while room $roomId is open — highlighting event $eventId"
                    )
                    pendingNotificationJumpEventId = eventId
                }
            } else {
                // Different room — try to pop back to room_list so its reactive handler navigates
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Navigation for different room $targetRoomId (current=$roomId), popping to room_list"
                )
                val poppedToRoomList = navController.popBackStack("room_list", inclusive = false)
                if (!poppedToRoomList) {
                    // room_list is not in the back stack — this happens when the app was launched
                    // directly into a room from a notification or shortcut (auth_check's popUpTo
                    // removes room_list). Navigate directly to the target room, replacing the
                    // current timeline so pressing Back doesn't return to the old room.
                    if (BuildConfig.DEBUG) Log.d(
                        "Andromuks",
                        "RoomTimelineScreen: room_list not in back stack, navigating directly to $targetRoomId"
                    )
                    val notificationTimestamp = appViewModel.getDirectRoomNavigationTimestamp()
                    appViewModel.clearDirectRoomNavigation()
                    appViewModel.flushSyncBatchForRoom(targetRoomId)
                    appViewModel.navigateToRoomWithCache(targetRoomId, notificationTimestamp)
                    navController.navigate("room_timeline/$targetRoomId") {
                        popUpTo("room_timeline/$roomId") { inclusive = true }
                    }
                }
                // If poppedToRoomList == true, RoomListScreen's LaunchedEffect handles the rest
            }
        }
    }

    // Auto-clear highlight after a short duration
    LaunchedEffect(highlightRequestId, highlightedEventId) {
        val currentRequest = highlightRequestId
        if (highlightedEventId != null && currentRequest > 0) {
            kotlinx.coroutines.delay(1600)
            if (highlightRequestId == currentRequest) {
                highlightedEventId = null
            }
        }
    }

    // Media picker state
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMediaIsVideo by remember { mutableStateOf(false) }
    var showMediaPreview by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    
    // Multiple media items (e.g. from share) for batch preview and send
    var selectedMediaItems by remember { mutableStateOf<List<SharedMediaItem>?>(null) }
    
    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    // In-app camera snapping overlay (rememberSaveable so it survives rotation/config changes)
    var showCameraOverlay by rememberSaveable { mutableStateOf(false) }
    // Simple flash mode cycling for CameraX integration
    var cameraFlashMode by rememberSaveable { mutableStateOf(CameraFlashMode.OFF) }
    // Camera selection: back or front
    var useFrontCamera by rememberSaveable { mutableStateOf(false) }
    // CameraX ImageCapture instance, provided by preview when ready
    var cameraImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    // Video overlay state (parallel to photo overlay)
    var showVideoOverlay by rememberSaveable { mutableStateOf(false) }
    var videoFlashMode by rememberSaveable { mutableStateOf(CameraFlashMode.OFF) }
    var videoUseFrontCamera by rememberSaveable { mutableStateOf(false) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeVideoRecording by remember { mutableStateOf<Recording?>(null) }
    var videoRecordingStartTime by remember { mutableStateOf<Long?>(null) }
    var videoRecordingElapsedSeconds by remember { mutableStateOf(0) }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    
    // Camera state
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var cameraVideoUri by remember { mutableStateOf<Uri?>(null) }

    // Text input state (moved here to be accessible by mention handler and share intake)
    var draft by remember { mutableStateOf("") }
    var lastTypingTime by remember { mutableStateOf(0L) }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    
    // Focus requester for text field (to focus when replying)
    val textFieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Track text field height to match button heights
    var textFieldHeight by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val buttonHeight = remember(textFieldHeight) {
        if (textFieldHeight > 0) {
            with(density) { textFieldHeight.toDp() }
        } else {
            40.dp // Fallback height (will be updated when text field is measured)
        }
    }

    // PERFORMANCE FIX: Use derivedStateOf to only recompose when keyboard state (open/closed) changes
    // This reduces recomposition from ~60fps to 2 (open + close) by only updating when boolean changes
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val isKeyboardOpen by remember {
        derivedStateOf {
            imeBottom > 50.dp
        }
    }

    LaunchedEffect(appViewModel.pendingShareUpdateCounter) {
        val sharePayload = appViewModel.consumePendingShareForRoom(roomId)
        if (sharePayload != null) {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Received pending share for room $roomId with ${sharePayload.items.size} items"
            )
            if (!sharePayload.text.isNullOrBlank() && draft.isBlank()) {
                draft = sharePayload.text
            }

            // Reset previous selections
            selectedMediaUri = null
            selectedAudioUri = null
            selectedFileUri = null
            selectedMediaIsVideo = false
            showAttachmentMenu = false

            if (sharePayload.items.size > 1) {
                selectedMediaItems = sharePayload.items
            } else {
                val shareItem = sharePayload.items.firstOrNull()
                if (shareItem != null) {
                    try {
                        val uri = shareItem.uri
                        val resolvedMime =
                            shareItem.mimeType ?: context.contentResolver.getType(uri) ?: ""

                        when {
                            resolvedMime.startsWith("image/") -> {
                                selectedMediaUri = uri
                                selectedMediaIsVideo = false
                                showMediaPreview = true
                            }
                            resolvedMime.startsWith("video/") -> {
                                selectedMediaUri = uri
                                selectedMediaIsVideo = true
                                showMediaPreview = true
                            }
                            resolvedMime.startsWith("audio/") -> {
                                selectedAudioUri = uri
                                showMediaPreview = true
                            }
                            else -> {
                                selectedFileUri = uri
                                selectedMediaIsVideo = resolvedMime.startsWith("video/")
                                showMediaPreview = true
                            }
                        }
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w("Andromuks", "RoomTimelineScreen: Failed to resolve shared media URI", e)
                    }
                }
            }
        }
    }
    
    // Room joiner state
    var showRoomJoiner by remember { mutableStateOf(false) }
    var roomLinkToJoin by remember { mutableStateOf<RoomLink?>(null) }
    
    // Mention state
    var showMentionList by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionStartIndex by remember { mutableStateOf(-1) }
    var isWaitingForFullMemberList by remember { mutableStateOf(false) }
    var lastMemberUpdateCounterBeforeMention by remember { mutableStateOf(appViewModel.memberUpdateCounter) }
    
    // Emoji shortcode ( :shortname: ) state
    var showEmojiSuggestionList by remember { mutableStateOf(false) }
    var emojiQuery by remember { mutableStateOf("") }
    var emojiStartIndex by remember { mutableStateOf(-1) }
    
    // Room mention ( #roomalias ) state
    var showRoomSuggestionList by remember { mutableStateOf(false) }
    var roomQuery by remember { mutableStateOf("") }
    var roomStartIndex by remember { mutableStateOf(-1) }
    
    // Command ( /command ) state
    var showCommandSuggestionList by remember { mutableStateOf(false) }
    var commandQuery by remember { mutableStateOf("") }
    var commandStartIndex by remember { mutableStateOf(-1) }
    
    // Avatar command state (for commands that need image picker)
    var pendingAvatarCommand by remember { mutableStateOf<String?>(null) } // "myroomavatar", "globalavatar", or "roomavatar"
    
    // Message menu state (for bottom menu bar)
    var messageMenuConfig by remember { mutableStateOf<MessageMenuConfig?>(null) }
    var retainedMessageMenuConfig by remember { mutableStateOf<MessageMenuConfig?>(null) }
    var showReactionsDialog by remember { mutableStateOf(false) }
    var reactionsEventId by remember { mutableStateOf<String?>(null) }
    var showBridgeDeliveryDialog by remember { mutableStateOf(false) }
    var bridgeDeliveryEventId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(messageMenuConfig) {
        if (messageMenuConfig != null) {
            retainedMessageMenuConfig = messageMenuConfig
        }
    }
    
    // Sync draft with TextFieldValue
    LaunchedEffect(draft) {
        if (textFieldValue.text != draft) {
            textFieldValue = textFieldValue.copy(text = draft, selection = TextRange(draft.length))
        }
    }
    
    // Pre-fill draft when editing starts
    LaunchedEffect(editingEvent) {
        if (editingEvent != null) {
            // Use ViewModel helper so E2EE + sync_complete edits (m.new_content only) pre-fill correctly
            val body = appViewModel.getBodyTextForEdit(editingEvent!!)
            draft = body
            
            // Hide mention list when editing
            showMentionList = false
        }
    }
    
    // Hide mention list when replying starts and focus text field with keyboard
    LaunchedEffect(replyingToEvent) {
        if (replyingToEvent != null) {
            showMentionList = false
            isWaitingForFullMemberList = false
            // Focus text field and show keyboard
            kotlinx.coroutines.delay(100) // Small delay to ensure UI is ready
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    
    // Show mention list when full member list is loaded
    LaunchedEffect(appViewModel.memberUpdateCounter, isWaitingForFullMemberList) {
        if (isWaitingForFullMemberList && appViewModel.memberUpdateCounter > lastMemberUpdateCounterBeforeMention) {
            // Full member list has been loaded, now show the mention list
            val memberMap = appViewModel.getMemberMap(roomId)
            if (memberMap.isNotEmpty()) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Full member list loaded (${memberMap.size} members), showing mention list")
                showMentionList = true
                isWaitingForFullMemberList = false
            }
        }
    }
    
    // Hide attachment menu when editing or replying starts
    LaunchedEffect(editingEvent, replyingToEvent) {
        if (editingEvent != null || replyingToEvent != null) {
            showAttachmentMenu = false
        }
    }

    // PERFORMANCE: Typing detection with debouncing - UI level rate limiting removed 
    // since AppViewModel.sendTyping() now handles rate limiting internally (3 seconds)
    LaunchedEffect(draft) {
        if (draft.isNotBlank()) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTypingTime > 3000) { // Reduced frequency: every 3 seconds
                appViewModel.sendTyping(roomId)
                lastTypingTime = currentTime
            }
        }
    }

    // Helper function to check if we need to request media permissions
    fun needsMediaPermissions(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    // Helper function to check if we have the necessary permissions for a specific picker type
    fun hasRequiredMediaPermissions(pickerType: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (pickerType) {
                "image" -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                "audio" -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                "file" -> {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                else -> false
            }
        } else {
            true // No need for these permissions on older Android versions
        }
    }

    // State to track which picker we're trying to launch after permission request
    var pendingMediaPickerType by remember { mutableStateOf("") }

    // Avatar image picker launcher (for avatar commands)
    val avatarImagePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                val mimeType = context.contentResolver.getType(it)
                if (mimeType?.startsWith("image/") == true) {
                    // Handle avatar upload
                    val command = pendingAvatarCommand
                    pendingAvatarCommand = null
                    
                    if (command != null) {
                        coroutineScope.launch {
                            try {
                                // Upload the image
                                val uploadResult = MediaUploadUtils.uploadMedia(
                                    context = context,
                                    uri = it,
                                    homeserverUrl = homeserverUrl,
                                    authToken = authToken,
                                    isEncrypted = false,
                                    compressOriginal = false
                                )
                                
                                if (uploadResult != null) {
                                    // Set the avatar based on command type
                                    when (command) {
                                        "myroomavatar" -> {
                                            appViewModel.setRoomMemberAvatar(roomId, uploadResult.mxcUrl)
                                            android.widget.Toast.makeText(
                                                context,
                                                "Room avatar updated",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        "globalavatar" -> {
                                            appViewModel.setGlobalAvatar(uploadResult.mxcUrl)
                                            android.widget.Toast.makeText(
                                                context,
                                                "Global avatar updated",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        "roomavatar" -> {
                                            appViewModel.setRoomAvatar(roomId, uploadResult.mxcUrl)
                                            android.widget.Toast.makeText(
                                                context,
                                                "Room avatar updated",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Failed to upload avatar",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Andromuks", "RoomTimelineScreen: Avatar upload error", e)
                                android.widget.Toast.makeText(
                                    context,
                                    "Error uploading avatar: ${e.message}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Please select an image file",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    pendingAvatarCommand = null
                }
            }
        }
    
    // Media picker launcher - accepts both images and videos
    val mediaPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) {
            uri: Uri? ->
            uri?.let {
                // Check if this is an image or video file
                val mimeType = context.contentResolver.getType(it)
                val isImageOrVideo = mimeType?.startsWith("image/") == true || mimeType?.startsWith("video/") == true
                
                if (isImageOrVideo) {
                    selectedMediaUri = it
                    // Detect if this is a video or image
                    selectedMediaIsVideo = mimeType?.startsWith("video/") == true
                    showMediaPreview = true
                } else {
                    // Show error message for non-image/video files
                    android.widget.Toast.makeText(
                        context,
                        "Please select an image or video file",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    
    // Audio picker launcher
    val audioPickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) {
            uri: Uri? ->
            uri?.let {
                selectedAudioUri = it
                showMediaPreview = true
            }
        }
    
    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) {
            uri: Uri? ->
            uri?.let {
                selectedFileUri = it
                // Detect if this is a video file
                val mimeType = context.contentResolver.getType(it)
                selectedMediaIsVideo = mimeType?.startsWith("video/") == true
                showMediaPreview = true
            }
        }
    
    // Camera photo launcher
    val cameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraPhotoUri != null) {
            cameraPhotoUri?.let { uri ->
                selectedMediaUri = uri
                selectedMediaIsVideo = false
                showMediaPreview = true
            }
        }
        cameraPhotoUri = null
    }
    
    // Camera video launcher
    val cameraVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success && cameraVideoUri != null) {
            cameraVideoUri?.let { uri ->
                selectedMediaUri = uri
                selectedMediaIsVideo = true
                showMediaPreview = true
            }
        }
        cameraVideoUri = null
    }
    
    // Helper function to create camera file URI
    fun createCameraFileUri(isVideo: Boolean): Uri? {
        return try {
            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = if (isVideo) "VID_${timeStamp}.mp4" else "IMG_${timeStamp}.jpg"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES)
                }
            }
            context.contentResolver.insert(
                if (isVideo) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
        } catch (e: Exception) {
            Log.e("Andromuks", "Error creating camera file URI", e)
            null
        }
    }
    
    // Camera permission launcher for photo
    val cameraPhotoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = createCameraFileUri(false) // Photo
            if (uri != null) {
                cameraPhotoUri = uri
                cameraPhotoLauncher.launch(uri)
                showAttachmentMenu = false
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Error creating camera file",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            android.widget.Toast.makeText(
                context,
                "Camera permission is required to take photos",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    // Camera permission launcher for video
    val cameraVideoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = createCameraFileUri(true) // Video
            if (uri != null) {
                cameraVideoUri = uri
                cameraVideoLauncher.launch(uri)
                showAttachmentMenu = false
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Error creating camera file",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            android.widget.Toast.makeText(
                context,
                "Camera permission is required to record videos",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    // Helper function to launch camera
    fun launchCamera(isVideo: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val uri = createCameraFileUri(isVideo)
            if (uri != null) {
                if (isVideo) {
                    cameraVideoUri = uri
                    cameraVideoLauncher.launch(uri)
                } else {
                    cameraPhotoUri = uri
                    cameraPhotoLauncher.launch(uri)
                }
                showAttachmentMenu = false
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Error creating camera file",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            if (isVideo) {
                cameraVideoPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                cameraPhotoPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Permission request launcher for media permissions
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasImagesPermission = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        val hasVideoPermission = permissions[Manifest.permission.READ_MEDIA_VIDEO] ?: false
        val hasAudioPermission = permissions[Manifest.permission.READ_MEDIA_AUDIO] ?: false
        
        // Check if we have the necessary permissions for the requested picker type
        val hasRequiredPermissions = when (pendingMediaPickerType) {
            "image" -> hasImagesPermission && hasVideoPermission
            "audio" -> hasAudioPermission
            "file" -> hasImagesPermission && hasVideoPermission && hasAudioPermission
            else -> false
        }
        
        // If permissions are granted, launch the appropriate picker
        if (hasRequiredPermissions) {
            when (pendingMediaPickerType) {
                "image" -> {
                    if (pendingAvatarCommand != null) {
                        avatarImagePickerLauncher.launch("image/*")
                    } else {
                        mediaPickerLauncher.launch("image/*,video/*")
                    }
                }
                "audio" -> audioPickerLauncher.launch("audio/*")
                "file" -> filePickerLauncher.launch("*/*")
            }
        }
    }

    // Helper function to launch picker with permission check
    fun launchPickerWithPermission(pickerType: String, mimeType: String) {
        if (needsMediaPermissions() && !hasRequiredMediaPermissions(pickerType)) {
            // Need to request permissions first
            pendingMediaPickerType = pickerType
            when (pickerType) {
                "image" -> {
                    mediaPermissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    ))
                }
                "audio" -> {
                    // For audio, we only need to request audio permission
                    mediaPermissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_AUDIO
                    ))
                }
                "file" -> {
                    // For files, request all media permissions as files can be anything
                    mediaPermissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                    ))
                }
            }
        } else {
            // Permissions already granted, launch directly
            when (pickerType) {
                "image" -> mediaPickerLauncher.launch(mimeType)
                "audio" -> audioPickerLauncher.launch(mimeType)
                "file" -> filePickerLauncher.launch(mimeType)
            }
        }
    }

    // (removed userProfileCache building loop - it was unused and caused main thread jank)

    // Get current room members for mention list (exclude current user and filter out invalid entries)
    val roomMembers = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId).filter { (userId, profile) ->
            // Exclude current user
            userId != myUserId &&
            // Ensure userId is a valid Matrix user ID format (@user:domain)
            userId.startsWith("@") && 
            userId.contains(":") &&
            // Ensure userId is not empty or malformed
            userId.length > 3
        }
    }
    
    // Get rooms with canonical aliases for room mentions
    val roomsWithAliases = remember(appViewModel.allRooms) {
        appViewModel.getRoomsWithCanonicalAliases()
    }

    // Mention detection and handling functions
    fun detectMention(text: String, cursorPosition: Int): Pair<String, Int>? {
        if (text.isEmpty() || cursorPosition < 0 || cursorPosition > text.length) return null
        
        // Look for @ at or before cursor position
        var atIndex = -1
        for (i in (cursorPosition - 1) downTo 0) {
            if (i < text.length && text[i] == '@') {
                atIndex = i
                break
            }
            // Stop if we hit a space or newline before finding @
            if (i < text.length && (text[i] == ' ' || text[i] == '\n')) {
                break
            }
        }
        
        // Also check if cursor is right after @ at the beginning or after space
        if (atIndex == -1 && cursorPosition > 0 && cursorPosition <= text.length) {
            if (text[cursorPosition - 1] == '@') {
                // Check if @ is at beginning or preceded by space/newline
                if (cursorPosition == 1 || (cursorPosition > 1 && (text[cursorPosition - 2] == ' ' || text[cursorPosition - 2] == '\n'))) {
                    atIndex = cursorPosition - 1
                }
            }
        }
        
        if (atIndex == -1) return null
        
        // Extract the query after @
        val queryStart = atIndex + 1
        var queryEnd = cursorPosition
        
        // Look for space after cursor position to find end of mention
        if (cursorPosition < text.length) {
            for (i in cursorPosition until text.length) {
                if (text[i] == ' ' || text[i] == '\n') {
                    queryEnd = i
                    break
                }
                queryEnd = i + 1
            }
        }
        
        // Allow showing mention list even if we just typed @ (empty query)
        if (queryStart <= cursorPosition) {
            val query = if (queryStart < min(queryEnd, text.length)) {
                text.substring(queryStart, min(queryEnd, text.length))
            } else {
                "" // Empty query when just @ is typed
            }
            return Pair(query, atIndex)
        }
        
        return null
    }

    // Emoji shortcode detection function (for ':' based autocomplete)
    fun detectEmojiShortcode(text: String, cursorPosition: Int): Pair<String, Int>? {
        if (text.isEmpty() || cursorPosition < 0 || cursorPosition > text.length) return null
        
        // Look for ':' at or before cursor position
        var colonIndex = -1
        for (i in (cursorPosition - 1) downTo 0) {
            val c = text[i]
            if (c == ':') {
                colonIndex = i
                break
            }
            // Stop if we hit a delimiter before finding ':'
            if (c == ' ' || c == '\n' || c == '\t') {
                break
            }
        }
        
        if (colonIndex == -1) return null
        
        // Ensure ':' is at start of text or preceded by whitespace/newline
        if (colonIndex > 0) {
            val prev = text[colonIndex - 1]
            if (prev != ' ' && prev != '\n' && prev != '\t') {
                return null
            }
        }
        
        val queryStart = colonIndex + 1
        var queryEnd = cursorPosition
        
        // Stop query at next delimiter or second ':'
        if (cursorPosition < text.length) {
            for (i in cursorPosition until text.length) {
                val c = text[i]
                if (c == ' ' || c == '\n' || c == '\t' || c == ':') {
                    break
                }
                queryEnd = i + 1
            }
        }
        
        if (queryStart <= cursorPosition) {
            val safeEnd = min(queryEnd, text.length)
            val query =
                if (queryStart < safeEnd) text.substring(queryStart, safeEnd) else ""
            return Pair(query, colonIndex)
        }
        
        return null
    }

    // Room mention detection function (for '#' based autocomplete)
    fun detectRoomMention(text: String, cursorPosition: Int): Pair<String, Int>? {
        if (text.isEmpty() || cursorPosition < 0 || cursorPosition > text.length) return null
        
        // Look for '#' at or before cursor position
        var hashIndex = -1
        for (i in (cursorPosition - 1) downTo 0) {
            if (i < text.length && text[i] == '#') {
                hashIndex = i
                break
            }
            // Stop if we hit a space or newline before finding #
            if (i < text.length && (text[i] == ' ' || text[i] == '\n')) {
                break
            }
        }
        
        // Also check if cursor is right after # (similar to mention detection)
        if (hashIndex == -1 && cursorPosition > 0 && cursorPosition <= text.length) {
            if (text[cursorPosition - 1] == '#') {
                // Check if # is at beginning or preceded by space/newline (same logic as mention detection)
                if (cursorPosition == 1 || (cursorPosition > 1 && (text[cursorPosition - 2] == ' ' || text[cursorPosition - 2] == '\n'))) {
                    hashIndex = cursorPosition - 1
                }
            }
        }
        
        if (hashIndex == -1) {
            if (BuildConfig.DEBUG && text.contains('#')) {
                Log.d("Andromuks", "RoomTimelineScreen: detectRoomMention - # found in text but hashIndex is -1, text='$text', cursorPosition=$cursorPosition")
            }
            return null
        }
        
        // Extract the query after #
        val queryStart = hashIndex + 1
        var queryEnd = cursorPosition
        
        // Look for space after cursor position to find end of mention
        if (cursorPosition < text.length) {
            for (i in cursorPosition until text.length) {
                if (text[i] == ' ' || text[i] == '\n') {
                    queryEnd = i
                    break
                }
                queryEnd = i + 1
            }
        }
        
        // Allow showing room list even if we just typed # (empty query)
        if (queryStart <= cursorPosition) {
            val query = if (queryStart < min(queryEnd, text.length)) {
                text.substring(queryStart, min(queryEnd, text.length))
            } else {
                "" // Empty query when just # is typed
            }
            return Pair(query, hashIndex)
        }
        
        return null
    }

    // Command detection function (for '/' based autocomplete)
    // Commands only trigger when '/' is the very first character.
    fun detectCommand(text: String, cursorPosition: Int): Pair<String, Int>? {
        if (text.isEmpty() || cursorPosition < 0 || cursorPosition > text.length) return null
        
        // Look for '/' at or before cursor position
        var slashIndex = -1
        for (i in (cursorPosition - 1) downTo 0) {
            if (i < text.length && text[i] == '/') {
                slashIndex = i
                break
            }
            // Stop if we hit a space or newline before finding /
            if (i < text.length && (text[i] == ' ' || text[i] == '\n')) {
                break
            }
        }
        
        // Also check if cursor is right after / (similar to mention detection)
        if (slashIndex == -1 && cursorPosition > 0 && cursorPosition <= text.length) {
            if (text[cursorPosition - 1] == '/') {
                // '/' must be at the very start of the input.
                if (cursorPosition == 1) {
                    slashIndex = cursorPosition - 1
                }
            }
        }
        
        if (slashIndex == -1) return null
        if (slashIndex != 0) return null
        
        // Extract the query after / (only the command name, up to first space/newline or cursor)
        val queryStart = slashIndex + 1
        var queryEnd = cursorPosition
        
        // Find the first space or newline after / and before/at cursor position
        for (i in queryStart until min(cursorPosition, text.length)) {
            if (text[i] == ' ' || text[i] == '\n') {
                queryEnd = i
                break
            }
        }
        
        // Allow showing command list even if we just typed / (empty query)
        if (queryStart <= cursorPosition) {
            val query = if (queryStart < min(queryEnd, text.length)) {
                text.substring(queryStart, min(queryEnd, text.length)).trim()
            } else {
                "" // Empty query when just / is typed
            }
            return Pair(query, slashIndex)
        }
        
        return null
    }

    // Handle backspace deletion of custom emoji markdown
    fun handleCustomEmojiDeletion(
        oldValue: TextFieldValue,
        newValue: TextFieldValue
    ): TextFieldValue {
        // Check if text was deleted (backspace was pressed)
        if (newValue.text.length >= oldValue.text.length) return newValue
        
        val oldText = oldValue.text
        val newText = newValue.text
        val cursor = newValue.selection.start
        val deletedLength = oldText.length - newText.length
        
        // Regex for custom emoji markdown: ![:name:](mxc://url "Emoji: :name:")
        val customEmojiRegex = Regex("""!\[:([^:]+):\]\((mxc://[^)]+)\s+"[^"]*"\)""")
        
        // Find all custom emoji markdowns in the old text
        val matches = customEmojiRegex.findAll(oldText).toList()
        
        // Check if cursor is within or right after a custom emoji markdown
        for (match in matches) {
            val markdownStart = match.range.first
            val markdownEnd = match.range.last + 1
            
            // Only trigger if cursor is inside the markdown, not at the boundary
            if (cursor >= markdownStart && cursor < markdownEnd && deletedLength == 1) {
                // User is deleting the custom emoji, remove the entire markdown
                val beforeMarkdown = oldText.substring(0, markdownStart)
                val afterMarkdown = oldText.substring(markdownEnd)
                val finalText = beforeMarkdown + afterMarkdown
                val finalCursor = markdownStart
                
                return TextFieldValue(
                    text = finalText,
                    selection = TextRange(finalCursor)
                )
            }
        }
        
        return newValue
    }

    // Replace completed :shortcode: with its emoji/custom emoji representation
    fun applyCompletedEmojiShortcode(
        value: TextFieldValue
    ): TextFieldValue {
        val text = value.text
        val cursor = value.selection.start
        if (cursor <= 0 || cursor > text.length) return value
        if (text[cursor - 1] != ':') return value
        
        // Find matching opening ':'
        var start = cursor - 2
        while (start >= 0) {
            val c = text[start]
            if (c == ':') {
                break
            }
            if (c == ' ' || c == '\n' || c == '\t') {
                return value
            }
            start--
        }
        
        if (start < 0 || text[start] != ':') return value
        
        val nameStart = start + 1
        val nameEnd = cursor - 1
        if (nameEnd <= nameStart) return value
        
        val shortcode = text.substring(nameStart, nameEnd)
        val suggestion =
            EmojiShortcodes.findByShortcode(shortcode, appViewModel.customEmojiPacks)
                ?: return value
        
        val replacement =
            suggestion.emoji
                ?: suggestion.customEmoji?.let { custom ->
                    "![:${custom.name}:](${custom.mxcUrl} \"Emoji: :${custom.name}:\")"
                }
                ?: return value
        
        val newText =
            text.substring(0, start) + replacement + text.substring(cursor)
        val newCursorPos = start + replacement.length
        
        return TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPos)
        )
    }

    fun handleMentionSelection(userId: String, displayName: String?, originalText: String, startIndex: Int, endIndex: Int): String {
        // Escape square brackets in display name to prevent regex issues
        val escapedDisplayName = (displayName ?: userId.removePrefix("@"))
            .replace("[", "\\[")
            .replace("]", "\\]")
        val mentionText = "[$escapedDisplayName](https://matrix.to/#/$userId)"
        return originalText.substring(0, startIndex) + mentionText + originalText.substring(endIndex)
    }

    // Define allowed event types (whitelist approach)
    // Note: m.room.redaction events are explicitly excluded as they should not appear in timeline
    val allowedEventTypes =
        setOf(
            "m.room.message",
            "m.room.encrypted",
            "m.room.member",
            "m.room.name",
            "m.room.topic",
            "m.room.avatar",
            "m.room.pinned_events",
            "m.room.tombstone",
            "m.reaction",
            "m.sticker"
            // m.room.redaction is intentionally excluded - redaction events should not appear in
            // timeline
        )

    // PERFORMANCE: Use background processing for heavy filtering and sorting operations
    var sortedEvents by remember { mutableStateOf<List<TimelineEvent>>(emptyList()) }
    
    // Process timeline events in background when this room's timeline changes.
    // IMPORTANT: Do NOT key this effect on global counters (like timelineUpdateCounter),
    // otherwise updates in other rooms would trigger unnecessary work here.
    // PERFORMANCE: Gate logging on app visibility and current room, but still process events
    // (needed for when app comes back to foreground)
    LaunchedEffect(timelineEvents) {
        val shouldLog = appViewModel.isAppVisible && appViewModel.currentRoomId == roomId
        if (shouldLog && BuildConfig.DEBUG) {
            Log.d(
                "Andromuks",
                "RoomTimelineScreen: Processing timelineEvents update - size=${timelineEvents.size}, updateCounter=${appViewModel.timelineUpdateCounter}, roomId=$roomId"
            )
        }
        sortedEvents = processTimelineEvents(
            timelineEvents = timelineEvents,
            allowedEventTypes = allowedEventTypes
        )
    }

    // Get base member map that observes memberUpdateCounter.
    // Declared here (before the profile-loading LaunchedEffect below) so the LaunchedEffect
    // lambda can reference it to detect senders whose profile is cached but not yet in the map.
    // CRITICAL FIX: Don't depend on sortedEvents directly to avoid infinite recomposition loop
    val memberMap = remember(roomId, appViewModel.memberUpdateCounter) {
        appViewModel.getMemberMap(roomId)
    }

    // PERFORMANCE: Pre-load all user profiles when timeline loads
    LaunchedEffect(timelineEvents) {
        if (appViewModel.isAppVisible && appViewModel.currentRoomId == roomId) {
            val uniqueSenders = timelineEvents.map { it.sender }.toSet()
            var needsMemberMapRefresh = false

            uniqueSenders.forEach { sender ->
                val existingProfile = appViewModel.getUserProfile(sender, roomId)
                if (existingProfile == null || existingProfile.displayName.isNullOrBlank()) {
                    appViewModel.requestUserProfileOnDemand(sender, roomId)
                } else if (!memberMap.containsKey(sender)) {
                    // Profile is cached but sender is not yet in memberMap (e.g. first message
                    // from this user in the current session). requestUserProfileOnDemand would
                    // skip the request, so memberUpdateCounter would never increment to include
                    // this sender. Force a recompute here instead.
                    needsMemberMapRefresh = true
                }
            }

            if (needsMemberMapRefresh) {
                appViewModel.memberUpdateCounter++
            }
        }
    }

    // PERFORMANCE: Create timeline items with date dividers and pre-compute consecutive flags.
    // Only depend on this room's sortedEvents; do NOT depend on global counters so that
    // events in other rooms don't cause recomputation here.
    // PERFORMANCE: Create timeline items with date dividers and pre-compute consecutive flags.
    // Use produceState to offload this heavy computation (iterating thousands of events) to a background thread.
    // The main thread should NEVER iterate the full event list.
    val timelineItems by produceState<List<TimelineItem>>(initialValue = emptyList(), sortedEvents) {
        value = withContext(Dispatchers.Default) {
            val items = mutableListOf<TimelineItem>()
            var lastDate: String? = null
            var previousEvent: TimelineEvent? = null

            for (event in sortedEvents) {
                if (event.type == "m.reaction") {
                    // Reactions mutate their target event and should not render as standalone timeline items
                    continue
                }

                // Format date inline to avoid @Composable context issue
                val date = Date(event.timestamp)
                val formatter = SimpleDateFormat("dd / MM / yyyy", Locale.getDefault())
                val eventDate = formatter.format(date)

                // Add date divider if this is a new date
                if (lastDate == null || eventDate != lastDate) {
                    items.add(TimelineItem.DateDivider(eventDate))
                    lastDate = eventDate
                    // Date divider breaks consecutive grouping
                    previousEvent = null
                }

                // Check if this event has per-message profile (from bridges like Beeper)
                val hasPerMessageProfile = 
                    event.content?.has("com.beeper.per_message_profile") == true ||
                    event.decrypted?.has("com.beeper.per_message_profile") == true

                // Check if this is a consecutive message from the same sender
                val isConsecutive = !hasPerMessageProfile && 
                    previousEvent?.sender == event.sender

                // Add the event with pre-computed flags
                items.add(TimelineItem.Event(
                    event = event,
                    isConsecutive = isConsecutive,
                    hasPerMessageProfile = hasPerMessageProfile
                ))

                previousEvent = event
            }
            items
        }
    }
    var lastInitialScrollSize by remember(roomId) { mutableStateOf(0) }

    // CRITICAL FIX: Use simple size-based key to avoid expensive operations during composition
    // Processing all senders with map/distinct/sorted can block UI thread and cause ANR
    // Using just size is sufficient - if size changes, we need to recompute anyway
    val sortedEventsSize = sortedEvents.size
    
    // CRITICAL FIX: Ensure current user profile is included in memberMapWithFallback
    // The current user's profile might not be in the room's member map if there's no m.room.member event for them
    // This fixes the issue where own messages show username instead of display name/avatar
    val memberMapWithFallback = remember(memberMap, appViewModel.currentUserProfile, myUserId) {
        val enhancedMap = memberMap.toMutableMap()
        
        // If current user is not in member map but we have currentUserProfile, add it
        if (myUserId.isNotBlank() && !enhancedMap.containsKey(myUserId)) {
            val currentProfile = appViewModel.currentUserProfile
            if (currentProfile != null) {
                enhancedMap[myUserId] = MemberProfile(
                    displayName = currentProfile.displayName,
                    avatarUrl = currentProfile.avatarUrl
                )
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Added current user profile to memberMapWithFallback - userId: $myUserId, displayName: ${currentProfile.displayName}"
                )
            }
        }
        
        enhancedMap
    }

    // List state and auto-scroll to bottom when data loads/changes
    val listState = rememberLazyListState()

    // True only during programmatic animated scrolls (FAB, keyboard, etc.).
    // Used by LocalIsScrollingFast so that image loading is suspended only during those scrolls,
    // NOT during slow manual scrolling where the user actually wants to see content.
    var isAnimatedScrolling by remember { mutableStateOf(false) }

    // Wrapper for every animateScrollToItem call site — sets/clears the flag atomically.
    suspend fun animatedScrollTo(index: Int, offset: Int = 0) {
        isAnimatedScrolling = true
        try {
            listState.animateScrollToItem(index, offset)
        } finally {
            isAnimatedScrolling = false
        }
    }

    // Prefetch guardband assets around the viewport (+50 above, +50 below).
    // Coil handles eviction naturally; we remove these keyed entries when leaving the room.
    val timelinePrefetchLoader = remember(context) { ImageLoaderSingleton.get(context) }
    val prefetchedTimelineMemoryKeys = remember(roomId) { mutableSetOf<String>() }
    val prefetchGuardband = 50

    fun enqueueTimelinePrefetch(
        mxcUrl: String?,
        keyPrefix: String,
        requestSize: Int
    ) {
        if (mxcUrl.isNullOrBlank()) return
        val httpUrl = AvatarUtils.mxcToHttpUrl(mxcUrl, homeserverUrl) ?: return
        val memoryKey = "timeline_prefetch:$roomId:$keyPrefix:${mxcUrl.hashCode()}"
        if (!prefetchedTimelineMemoryKeys.add(memoryKey)) return

        val request = ImageRequest.Builder(context)
            .data(httpUrl)
            .size(requestSize)
            .apply {
                if (httpUrl.startsWith("http")) {
                    addHeader("Cookie", "gomuks_auth=$authToken")
                }
            }
            .memoryCacheKey(memoryKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
        timelinePrefetchLoader.enqueue(request)
    }

    LaunchedEffect(listState, timelineItems, memberMapWithFallback, homeserverUrl, authToken, roomId) {
        snapshotFlow {
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (visibleIndices.isEmpty()) {
                null
            } else {
                (visibleIndices.minOrNull() ?: 0) to (visibleIndices.maxOrNull() ?: 0)
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (visibleStart, visibleEnd) ->
                if (timelineItems.isEmpty()) return@collect
                val start = (visibleStart - prefetchGuardband).coerceAtLeast(0)
                val end = (visibleEnd + prefetchGuardband).coerceAtMost(timelineItems.lastIndex)

                for (index in start..end) {
                    val item = timelineItems[index] as? TimelineItem.Event ?: continue
                    val event = item.event

                    // Prefetch sender avatar
                    val avatarMxc = memberMapWithFallback[event.sender]?.avatarUrl
                    enqueueTimelinePrefetch(
                        mxcUrl = avatarMxc,
                        keyPrefix = "avatar:${event.sender}",
                        requestSize = 256
                    )

                    // Prefetch media thumbnail (or media URL fallback) for image/video/sticker events
                    val content = when {
                        event.type == "m.room.message" -> event.content
                        event.type == "m.room.encrypted" && event.decryptedType == "m.room.message" -> event.decrypted
                        event.type == "m.sticker" -> event.content ?: event.decrypted
                        else -> null
                    }
                    val msgType = when {
                        event.type == "m.sticker" -> "m.sticker"
                        else -> content?.optString("msgtype", "")
                    }
                    if (msgType == "m.image" || msgType == "m.video" || msgType == "m.sticker") {
                        val info = content?.optJSONObject("info")
                        val thumbnailMxc =
                            info?.optJSONObject("thumbnail_file")
                                ?.optString("url")
                                ?.takeIf { it.isNotBlank() }
                                ?: info?.optString("thumbnail_url", "")?.takeIf { it.isNotBlank() }
                        val mediaMxc = content?.optString("url", "")?.takeIf { it.isNotBlank() }
                        enqueueTimelinePrefetch(
                            mxcUrl = thumbnailMxc ?: mediaMxc,
                            keyPrefix = "media:${event.eventId}",
                            requestSize = 512
                        )
                    }
                }
            }
    }

    DisposableEffect(roomId) {
        onDispose {
            val cache = timelinePrefetchLoader.memoryCache
            prefetchedTimelineMemoryKeys.forEach { key ->
                cache?.remove(MemoryCache.Key(key))
            }
            prefetchedTimelineMemoryKeys.clear()
        }
    }
    
    // Track scroll position for pagination restoration
    // With reverseLayout, we capture the highest visible index (oldest message at top)
    // After pagination adds older events, we scroll so that index is at the bottom of view
    var highestVisibleIndexBeforePagination by remember { mutableStateOf<Int?>(null) }
    var anchorScrollOffsetForRestore by remember { mutableStateOf(0) }
    var pendingScrollRestoration by remember { mutableStateOf(false) }
    var expectedTimelineSizeBeforePagination by remember { mutableStateOf<Int?>(null) }

    
    // Pull-to-refresh state
    var isRefreshingPull by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshingPull,
        onRefresh = {
            // Capture the highest visible index before pagination
            // With reverseLayout, highest index = oldest message at top of view
            // After pagination adds older events, we'll scroll so this index is at bottom of view
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            val highestVisibleIndex = visibleIndices.maxOrNull()
            
            if (highestVisibleIndex != null && timelineItems.isNotEmpty()) {
                highestVisibleIndexBeforePagination = highestVisibleIndex
                anchorScrollOffsetForRestore = listState.firstVisibleItemScrollOffset
                pendingScrollRestoration = true
                expectedTimelineSizeBeforePagination = timelineItems.size
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Pull-to-refresh triggered, capturing highest visible index: $highestVisibleIndex (out of ${timelineItems.size} items)"
                )
            } else {
                // Fallback: use first visible item index
                highestVisibleIndexBeforePagination = listState.firstVisibleItemIndex
                anchorScrollOffsetForRestore = listState.firstVisibleItemScrollOffset
                pendingScrollRestoration = true
                expectedTimelineSizeBeforePagination = timelineItems.size
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Pull-to-refresh triggered, no visible items, using first visible index: ${listState.firstVisibleItemIndex}"
                )
            }
            
            // Use the oldest event from cache, not the oldest rendered event
            // The cache may have events that aren't currently rendered, so we need to use
            // the absolute oldest event to avoid requesting duplicates
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Pull-to-refresh triggered, requesting pagination with oldest cached event")
            isRefreshingPull = true
            appViewModel.requestPaginationWithSmallestRowId(roomId, limit = 100)
        }
    )
    
    // Monitor pagination state to stop refresh indicator
    // Note: Refresh indicator is now cleared in scroll restoration LaunchedEffect
    // This is kept as a fallback in case scroll restoration doesn't trigger
    LaunchedEffect(appViewModel.isPaginating) {
        if (!appViewModel.isPaginating && isRefreshingPull && !pendingScrollRestoration) {
            isRefreshingPull = false
        }
    }

    // Track if user is "attached" to the bottom (sticky scroll)
    var isAttachedToBottom by remember { mutableStateOf(true) }
    
    // Track previous app visibility state to detect background/foreground transitions
    var previousAppVisibleState by remember(roomId) { mutableStateOf(appViewModel.isAppVisible) }
    
    // CRITICAL FIX: Immediately set scroll position to bottom when timelineItems first becomes available
    // With reverseLayout, index 0 is the bottom (newest message)
    var hasSetInitialScrollPosition by remember(roomId) { mutableStateOf(false) }
    LaunchedEffect(timelineItems.size, roomId) {
        if (timelineItems.isNotEmpty() && !hasSetInitialScrollPosition) {
            val threadReturnEventId = appViewModel.threadReturnScrollEventId
            if (threadReturnEventId != null) {
                // Returning from thread viewer — don't scroll to bottom here; the main
                // initial-scroll LaunchedEffect below will handle positioning.
            } else {
                // With reverseLayout, index 0 is the bottom (newest message)
                listState.scrollToItem(0)
                isAttachedToBottom = true
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Set initial scroll position to bottom (index=0, items=${timelineItems.size}) - reverseLayout anchors at bottom"
                )
            }
            hasSetInitialScrollPosition = true
        }
    }
    
    // CRITICAL FIX: When new items are added while attached, adjust scroll position immediately
    // With reverseLayout, index 0 is bottom (newest message)
    // CRITICAL: Skip during scroll restoration (pagination) to avoid jumping to bottom
    LaunchedEffect(timelineItems.size, isAttachedToBottom) {
        if (pendingScrollRestoration) {
            return@LaunchedEffect // Don't scroll during pagination scroll restoration
        }
        // This effect fires before the main initial-scroll effect (lower line number), so
        // pendingScrollRestoration isn't set yet on thread-viewer return. Check the event ID
        // directly — it's still non-null at this point because the initial-scroll effect
        // hasn't cleared it yet.
        if (appViewModel.threadReturnScrollEventId != null) {
            return@LaunchedEffect
        }
        if (timelineItems.isNotEmpty() && isAttachedToBottom && hasSetInitialScrollPosition) {
            val currentFirstVisible = listState.firstVisibleItemIndex
            
            // Only adjust if we're not already at bottom (index 0)
            if (currentFirstVisible > 0) {
                // Immediately adjust scroll position - happens in same frame as item addition
                listState.scrollToItem(0)
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Adjusted scroll position for new items (was at index=$currentFirstVisible, scrolled to 0) - reverseLayout"
                )
            }
        }
    }

    // Track if this is the first load (to avoid animation on initial room open)
    var isInitialLoad by remember { mutableStateOf(true) }
    
    // Track if we're refreshing (to scroll to bottom after refresh)
    var isRefreshing by remember { mutableStateOf(false) }

    var previousItemCount by remember { mutableStateOf(timelineItems.size) }
    var hasLoadedInitialBatch by remember { mutableStateOf(false) }
    var hasInitialSnapCompleted by remember { mutableStateOf(false) }
    var lastKnownTimelineEventId by remember { mutableStateOf<String?>(null) }
    var hasCompletedInitialLayout by remember { mutableStateOf(false) }
    var pendingInitialScroll by remember { mutableStateOf(true) }

    // REMOVED: isAtBottom derivedStateOf - replaced with direct scroll intent tracking
    // The derived state was fragile during keyboard transitions, causing false negatives

    // CRITICAL FIX: Track app visibility changes to handle background/foreground transitions
    // When app foregrounds, if we were attached to bottom, verify we're still at bottom and scroll if needed
    LaunchedEffect(appViewModel.isAppVisible, roomId) {
        val appJustBecameVisible = !previousAppVisibleState && appViewModel.isAppVisible

        // Reset animation cutover so only messages received *after* user is looking animate
        // (batched sync_completes on resume use older event timestamps and won't match).
        if (appJustBecameVisible) {
            appViewModel.markTimelineForeground(roomId)
        }

        if (appJustBecameVisible && isAttachedToBottom) {
            // App was foregrounded AND we're marked as attached - animate scroll to bottom smoothly
            // With reverseLayout, index 0 is bottom
            if (timelineItems.isNotEmpty()) {
                coroutineScope.launch {
                    animatedScrollTo(0)
                    if (BuildConfig.DEBUG) Log.d(
                        "Andromuks",
                        "RoomTimelineScreen: App resumed, animating scroll to bottom (index=0, items=${timelineItems.size}) - reverseLayout"
                    )
                }
            }
            
            // Also set up a delayed check in case more items arrive during batch processing
            kotlinx.coroutines.delay(150)
            
            // Re-check after a brief delay to catch any items added during batch processing
            if (timelineItems.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
                val currentFirstVisible = listState.firstVisibleItemIndex
                val actuallyAtBottom = currentFirstVisible == 0
                
                if (!actuallyAtBottom) {
                    // Still not at bottom after batch processing - animate scroll again
                    coroutineScope.launch {
                        animatedScrollTo(0)
                        if (BuildConfig.DEBUG) Log.d(
                            "Andromuks",
                            "RoomTimelineScreen: App resumed, adjusted animated scroll after batch (was at index=$currentFirstVisible, scrolled to 0)"
                        )
                    }
                }
            }
        }
        
        previousAppVisibleState = appViewModel.isAppVisible
    }
    
    // Bottom attachment: single snapshotFlow over (index, offset) so both read in one frame.
    // Avoids races between separate isScrollInProgress vs offset effects during keyboard transitions.
    // With reverseLayout=true, bottom == first item; offset tolerance matches Signal/WhatsApp UX.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .debounce(50L) // coalesce keyboard/layout jitter
            .collect { (index, offset) ->
                if (!hasInitialSnapCompleted || !hasLoadedInitialBatch || pendingScrollRestoration) {
                    return@collect
                }
                val atBottom = index == 0 && offset < 100
                if (!isKeyboardOpen) {
                    if (atBottom != isAttachedToBottom) {
                        isAttachedToBottom = atBottom
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "Andromuks",
                                "RoomTimelineScreen: Attachment updated, isAttachedToBottom=$atBottom (index=$index, offset=$offset)"
                            )
                        }
                    }
                } else {
                    // Same as BubbleTimelineScreen: allow detach while IME open so FAB can show
                    if (!atBottom && isAttachedToBottom) {
                        isAttachedToBottom = false
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                "Andromuks",
                                "RoomTimelineScreen: Keyboard open, user scrolled up — detached (index=$index, offset=$offset)"
                            )
                        }
                    }
                }
            }
    }
    
    // PERFORMANCE: Separate LaunchedEffect for auto-scroll when attached to bottom
    // This only triggers when timelineItems.size changes (new messages), not on every scroll
    // CRITICAL: Skip auto-scroll during keyboard transitions to prevent scroll position loss
    // CRITICAL: Skip during scroll restoration (pagination) to avoid jumping to bottom
    // PERFORMANCE: Use isKeyboardOpen derived state instead of imeBottom to reduce recomposition
    LaunchedEffect(timelineItems.size, isAttachedToBottom, isKeyboardOpen) {
        if (pendingScrollRestoration) {
            return@LaunchedEffect // Don't scroll during pagination scroll restoration
        }
        if (!hasInitialSnapCompleted || !hasLoadedInitialBatch) {
            return@LaunchedEffect
        }
        
        if (timelineItems.isEmpty() || listState.layoutInfo.totalItemsCount == 0) {
            return@LaunchedEffect
        }
        
        if (isKeyboardOpen && isAttachedToBottom) {
            // Keyboard is open and we're attached - just scroll to bottom for new messages
            // With reverseLayout, index 0 is bottom
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Keyboard open, attached to bottom, new message arrived. Scrolling to bottom (index=0)"
            )
            coroutineScope.launch {
                // Small delay to let message render
                kotlinx.coroutines.delay(50)
                listState.scrollToItem(0)
            }
            return@LaunchedEffect // Skip the position check below when keyboard is open
        }
        
        // CRITICAL: Only check scroll position when keyboard is CLOSED
        // With reverseLayout, firstVisibleItemIndex == 0 means at bottom
        if (!isKeyboardOpen && isAttachedToBottom && timelineItems.isNotEmpty()) {
            // Wait a moment for layout to settle after new items are added
            kotlinx.coroutines.delay(100)
            
            // Re-check conditions after delay
            if (listState.layoutInfo.totalItemsCount > 0 && timelineItems.isNotEmpty()) {
                val currentFirstVisible = listState.firstVisibleItemIndex
                val currentOffset = listState.firstVisibleItemScrollOffset
                // Match attachment tolerance (index==0 && offset<100) to avoid scroll fighting
                val actuallyAtBottom = currentFirstVisible == 0 && currentOffset < 100
                
                if (!actuallyAtBottom) {
                    // We're attached but not actually at bottom - scroll to bottom
                    if (BuildConfig.DEBUG) Log.d(
                        "Andromuks",
                        "RoomTimelineScreen: Keyboard closed, attached to bottom but not at bottom (firstVisible=$currentFirstVisible). Auto-scrolling to show new items."
                    )
                    coroutineScope.launch {
                        listState.scrollToItem(0)
                    }
                }
            }
        }
    }

    // Track previous pagination state to detect when pagination finishes
    var previousIsPaginating by remember { mutableStateOf(appViewModel.isPaginating) }
    
    // Detect when pagination completes and trigger scroll restoration
    // CRITICAL FIX: Only depend on isPaginating, not timelineItems.size
    // This prevents scroll restoration from triggering when new messages arrive
    LaunchedEffect(appViewModel.isPaginating) {
        val paginationJustFinished = previousIsPaginating && !appViewModel.isPaginating
        previousIsPaginating = appViewModel.isPaginating
        
        // When pagination finishes and we have scroll restoration pending
        if (paginationJustFinished && pendingScrollRestoration) {
            val highestIndex = highestVisibleIndexBeforePagination
            val oldSize = expectedTimelineSizeBeforePagination
            
            // CRITICAL: Check timelineEvents.size (from ViewModel) not timelineItems.size (from UI)
            // timelineItems.size may not be updated yet when this LaunchedEffect fires
            val newSize = appViewModel.timelineEvents.size
            
            if (highestIndex != null && oldSize != null && newSize > oldSize) {
                // With reverseLayout, new events are added at higher indices (older messages at top)
                // We want to scroll so that the old highest index is at the TOP of the view
                // Since new events were added, the old highest index is still valid
                // We scroll to it so it appears at the top of the viewport
                
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Pagination completed, restoring scroll. " +
                    "Old highest visible index: $highestIndex, old size: $oldSize, new size: $newSize"
                )
                
                // Wait for timelineItems to be rebuilt from timelineEvents
                // We need to wait for the UI to process the new events
                var waitCount = 0
                while (timelineItems.size <= oldSize && waitCount < 20) {
                    kotlinx.coroutines.delay(50)
                    waitCount++
                }
                
                // Wait for layout to settle after new items are added
                kotlinx.coroutines.delay(100)
                
                // Scroll so that the old highest index is at the TOP of the viewport
                // With reverseLayout, higher indices are at the top, so scrolling to this index
                // with offset 0 will place it at the top (where it was when pull-to-refresh was triggered)
                val targetIndex = highestIndex.coerceIn(0, timelineItems.lastIndex)
                
                // Use animateScrollToItem with smooth animation for smooth UX
                // scrollOffset = 0 ensures the item is at the top of the viewport
                // The animation duration is controlled by Compose's default animation
                animatedScrollTo(targetIndex, 0)
                
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: ✅ Scroll position restored to index $targetIndex (old highest visible index) at top of viewport"
                )
                
                // Wait for animation to complete (default is ~300-500ms) plus a bit more for layout to settle
                kotlinx.coroutines.delay(600)
            } else {
                // Fallback: maintain current scroll position
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Pagination completed, but no valid index captured or no new events. " +
                    "highestIndex=$highestIndex, oldSize=$oldSize, newSize=$newSize"
                )
                kotlinx.coroutines.delay(100)
                val currentFirstIndex = listState.firstVisibleItemIndex
                if (currentFirstIndex >= 0 && currentFirstIndex < timelineItems.size) {
                    listState.scrollToItem(currentFirstIndex, anchorScrollOffsetForRestore)
                }
                kotlinx.coroutines.delay(300)
            }
            
            // Clear restoration state AFTER scroll has completed and timeline has settled
            pendingScrollRestoration = false
            highestVisibleIndexBeforePagination = null
            anchorScrollOffsetForRestore = 0
            expectedTimelineSizeBeforePagination = null
            isRefreshingPull = false
        }
    }
    
    // REMOVED: This LaunchedEffect was clearing pendingScrollRestoration too early,
    // before scroll restoration completed. The scroll restoration LaunchedEffect now
    // handles clearing the flag after scroll completes.

    // When a manual refresh completes, snap back to bottom and re-enable auto-pagination
    // With reverseLayout, index 0 is bottom
    LaunchedEffect(isRefreshing, timelineItems.size) {
        if (isRefreshing && timelineItems.isNotEmpty() && !appViewModel.hasPendingTimelineRequest(roomId)) {
            listState.scrollToItem(0, 0)
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Manual refresh loaded ${timelineItems.size} items - scrolled to bottom (index=0)")
            isAttachedToBottom = true
            hasInitialSnapCompleted = true
            hasCompletedInitialLayout = true
            pendingScrollRestoration = false
            isRefreshing = false
        }
    }
    
    // Safety fallback: if refresh takes too long, re-enable auto-pagination to avoid being stuck
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            kotlinx.coroutines.delay(2000)
            if (isRefreshing && !appViewModel.hasPendingTimelineRequest(roomId)) {
                Log.w("Andromuks", "RoomTimelineScreen: Manual refresh timeout - marking refresh as complete (no pending requests)")
                isRefreshing = false
            }
        }
    }

    // Track last known timeline update counter to detect when timeline has been built.
    // NOTE: This is read for heuristics inside effects but we avoid keying effects on it,
    // so global timeline updates in other rooms don't force recomposition here.
    var lastKnownTimelineUpdateCounter by remember { mutableStateOf(appViewModel.timelineUpdateCounter) }
    
    // Auto-scroll to bottom only when attached (initial load or new messages while at bottom)
    LaunchedEffect(
        timelineItems.size,
        isLoading,
        appViewModel.isPaginating,
        pendingNotificationJumpEventId
    ) {
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "RoomTimelineScreen: LaunchedEffect - timelineItems.size: ${timelineItems.size}, isLoading: $isLoading, isPaginating: ${appViewModel.isPaginating}, timelineUpdateCounter: ${appViewModel.timelineUpdateCounter}, hasInitialSnapCompleted: $hasInitialSnapCompleted"
        )

        if (pendingNotificationJumpEventId != null) {
            return@LaunchedEffect
        }

        if (isLoading || timelineItems.isEmpty()) {
            return@LaunchedEffect
        }

        val lastEventId = (timelineItems.lastOrNull() as? TimelineItem.Event)?.event?.eventId

        if (!hasInitialSnapCompleted) {
            coroutineScope.launch {
                // OPTIMIZATION: For initial load, scroll immediately when events are available
                // Don't wait for stability - events from cache/DB are already stable
                // Only wait a brief moment to ensure timeline has been built at least once
                
                // Quick check: wait for timeline to be built (update counter > 0) OR wait max 200ms
                var waitCount = 0
                val maxWaitAttempts = 4 // Max 200ms (4 * 50ms)
                
                while (waitCount < maxWaitAttempts) {
                    val currentUpdateCounter = appViewModel.timelineUpdateCounter
                    val stillLoading = isLoading || appViewModel.isPaginating
                    val hasEvents = timelineItems.isNotEmpty()
                    
                    // Check if timeline has been built at least once (update counter changed from initial)
                    val timelineHasBeenBuilt = currentUpdateCounter != lastKnownTimelineUpdateCounter || currentUpdateCounter > 0
                    
                    // If we have events, timeline is built, and not loading - scroll immediately
                    if (hasEvents && timelineHasBeenBuilt && !stillLoading) {
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Timeline ready for immediate scroll (${timelineItems.size} items, updateCounter: $currentUpdateCounter) after ${waitCount * 50}ms")
                        lastKnownTimelineUpdateCounter = currentUpdateCounter
                        break
                    }
                    
                    // If still loading, wait a bit more
                    if (stillLoading) {
                        kotlinx.coroutines.delay(50)
                        waitCount++
                        continue
                    }
                    
                    // If no events yet but timeline counter changed, wait one more check
                    if (!hasEvents && timelineHasBeenBuilt) {
                        kotlinx.coroutines.delay(50)
                        waitCount++
                        continue
                    }
                    
                    // Otherwise, wait and check again
                    kotlinx.coroutines.delay(50)
                    waitCount++
                }
                
                // Final check before scrolling
                if (timelineItems.isEmpty() || (isLoading && waitCount >= maxWaitAttempts)) {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Timeline not ready for scroll (empty: ${timelineItems.isEmpty()}, loading: $isLoading, paginating: ${appViewModel.isPaginating})")
                    // Still mark as completed to avoid infinite loop
                    hasInitialSnapCompleted = true
                    return@launch
                }
                
                // Check if we're returning from a thread viewer — scroll to the tapped message
                // instead of jumping to the bottom. The event ID survives composable recreation
                // because it's stored in AppViewModel.
                val threadReturnEventId = appViewModel.threadReturnScrollEventId
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Initial scroll check - threadReturnScrollEventId=$threadReturnEventId, timelineItems=${timelineItems.size}")
                if (threadReturnEventId != null) {
                    // Suppress all other scroll effects BEFORE suspending at scrollToItem.
                    // Without this, the readinessCheckComplete LaunchedEffect races us: it wakes
                    // up while we're suspended at scrollToItem(targetIndex), sees the event ID
                    // already cleared, and calls scrollToItem(0) — overriding our restoration.
                    pendingScrollRestoration = true
                    appViewModel.threadReturnScrollEventId = null
                    val targetIndex = timelineItems.indexOfFirst {
                        (it as? TimelineItem.Event)?.event?.eventId == threadReturnEventId
                    }
                    if (targetIndex >= 0) {
                        // timelineItems is oldest-first, but the LazyColumn renders reversedTimelineItems
                        // (timelineItems.reversed()) with reverseLayout=true so index 0 = newest (visual bottom).
                        // Convert: reversedIndex = timelineItems.size - 1 - targetIndex
                        val reversedIndex = timelineItems.size - 1 - targetIndex
                        listState.scrollToItem(reversedIndex)
                        isAttachedToBottom = false
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: ✅ Restored scroll to thread-tapped event $threadReturnEventId at index=$targetIndex (reversedIndex=$reversedIndex) after returning from thread viewer")
                    } else {
                        // Event not found (e.g. paginated away) — fall back to bottom
                        pendingScrollRestoration = false
                        listState.scrollToItem(0)
                        isAttachedToBottom = true
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Thread return event $threadReturnEventId not found, falling back to bottom")
                    }
                } else {
                    // Normal initial load — scroll to bottom (newest message)
                    listState.scrollToItem(0)
                    isAttachedToBottom = true
                }
                hasInitialSnapCompleted = true
                hasLoadedInitialBatch = true
                previousItemCount = timelineItems.size
                lastKnownTimelineEventId = lastEventId
                lastKnownTimelineUpdateCounter = appViewModel.timelineUpdateCounter

                // Release suppression after a brief settle period so normal scroll tracking resumes.
                if (pendingScrollRestoration) {
                    kotlinx.coroutines.delay(150)
                    pendingScrollRestoration = false
                }

                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: ✅ Scrolled to bottom on initial load (${timelineItems.size} items, index=0, updateCounter: ${appViewModel.timelineUpdateCounter}) - reverseLayout")
            }
            return@LaunchedEffect
        }

        val hasNewItems = previousItemCount < timelineItems.size

        // Skip handling new items if we're waiting for scroll restoration after pagination
        if (pendingScrollRestoration) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Skipping new items handling - pending scroll restoration")
            return@LaunchedEffect
        }

        // CRITICAL FIX: Only auto-scroll for new messages if attached to bottom
        // BUT skip this if keyboard is open - let the other LaunchedEffect handle it
        // With reverseLayout, index 0 is bottom
        if (
            hasNewItems &&
                isAttachedToBottom &&
                lastEventId != null &&
                lastEventId != lastKnownTimelineEventId &&
                !isKeyboardOpen // ONLY handle when keyboard is CLOSED
        ) {
            coroutineScope.launch {
                // New-message scroll is a short hop — don't suppress images for it.
                listState.animateScrollToItem(0)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: New message arrived (keyboard closed), animateScrollToItem to bottom (index=0, attached=$isAttachedToBottom)")
            }
            lastKnownTimelineEventId = lastEventId
        }

        if (hasNewItems && lastEventId != null) {
            lastKnownTimelineEventId = lastEventId
        }

        if (!pendingScrollRestoration) {
            previousItemCount = timelineItems.size
        }
    }

    
    // NOTE: markRoomAsRead is handled by navigateToRoomWithCache, so we don't need to call it here
    // This prevents duplicate mark_read calls and race conditions

    LaunchedEffect(timelineItems.size, readinessCheckComplete, pendingInitialScroll) {
        if (pendingInitialScroll && readinessCheckComplete && timelineItems.isNotEmpty() &&
            timelineItems.size != lastInitialScrollSize) {
            coroutineScope.launch {
                // Skip bottom-scroll if the main initial-scroll effect is already handling
                // thread-return restoration (it sets pendingScrollRestoration before suspending).
                if (!pendingScrollRestoration) {
                    // With reverseLayout, index 0 is bottom
                    listState.scrollToItem(0)
                    isAttachedToBottom = true
                }
                hasInitialSnapCompleted = true
                pendingInitialScroll = false
                lastInitialScrollSize = timelineItems.size
            }
        }
    }

    LaunchedEffect(roomId) {
        // Capture before any suspension — the big initial-scroll effect clears this after it fires.
        val isThreadViewerReturn = appViewModel.threadReturnScrollEventId != null
        val isWarmTimelineReturn = appViewModel.currentRoomId == roomId && appViewModel.timelineEvents.isNotEmpty()
        readinessCheckComplete = isWarmTimelineReturn
        pendingInitialScroll = !isWarmTimelineReturn
        if (!isWarmTimelineReturn) {
            lastInitialScrollSize = 0
            highlightedEventId = null
            highlightRequestId = 0
        }
        appViewModel.promoteToPrimaryIfNeeded("room_timeline_$roomId")
        
        // PERFORMANCE FIX: Only call navigateToRoomWithCache if room isn't already loaded
        // RoomListScreen already calls it when user clicks, so we skip duplicate processing
        val isAlreadyLoaded = appViewModel.currentRoomId == roomId && appViewModel.timelineEvents.isNotEmpty()
        if (!isAlreadyLoaded) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Room $roomId not yet loaded, calling navigateToRoomWithCache")
            appViewModel.navigateToRoomWithCache(roomId)
        } else {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Room $roomId already loaded (${appViewModel.timelineEvents.size} events), skipping navigateToRoomWithCache")
        }
        
        if (!isWarmTimelineReturn) {
            val requireInitComplete = !appViewModel.isWebSocketConnected()
            val readinessResult = appViewModel.awaitRoomDataReadiness(requireInitComplete = requireInitComplete, roomId = roomId)
            readinessCheckComplete = true
            if (!readinessResult && BuildConfig.DEBUG) {
                Log.w("Andromuks", "RoomTimelineScreen: Readiness timeout while opening $roomId - continuing with partial data")
            }
        } else if (BuildConfig.DEBUG) {
            Log.d("Andromuks", "RoomTimelineScreen: Warm return for $roomId - skipping readiness wait")
        }
        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Loading timeline for room: $roomId")
        // CRITICAL FIX: Set currentRoomId immediately when screen opens (for all navigation paths)
        // This ensures state is consistent whether opening from RoomListScreen, notification, or shortcut
        appViewModel.setCurrentRoomIdForTimeline(roomId)
        // Cutover for entrance animation: only messages after user is on this screen (or last resume)
        if (appViewModel.isAppVisible) {
            appViewModel.markTimelineForeground(roomId)
        }
        // CRITICAL: Add room to opened rooms (exempt from cache clearing on WebSocket reconnect)
        // This is also done in setCurrentRoomIdForTimeline, but we ensure it here too
        RoomTimelineCache.addOpenedRoom(roomId)
        
        // Reset state for new room.
        // Skip the scroll-related resets when returning from thread viewer — the big
        // initial-scroll LaunchedEffect already restored the position and owns those flags.
        if (!isThreadViewerReturn) {
            pendingScrollRestoration = false
            isAttachedToBottom = true
            isInitialLoad = true
            hasInitialSnapCompleted = false
        }
        highestVisibleIndexBeforePagination = null
        hasLoadedInitialBatch = false
        
        // CRITICAL FIX: Ensure loading state is set early when opening a new room
        // This ensures "Room loading..." shows immediately while room data is being loaded/processed
        if (appViewModel.currentRoomId != roomId || appViewModel.timelineEvents.isEmpty()) {
            // Only set loading if we're opening a different room or timeline is empty
            // This prevents showing loading when resuming the same room
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Setting loading state for room: $roomId")
        }
        
        // Request room state on cold open only to avoid jank on warm pop-back from UserInfo.
        if (!isWarmTimelineReturn) {
            // NOTE: navigateToRoomWithCache() already calls requestRoomTimeline() if cache is empty,
            // so we don't need to call it again here to avoid duplicate paginate requests
            appViewModel.requestRoomState(roomId)
        }
    }
    
    // CRITICAL FIX: Clear currentRoomId when leaving the room (back navigation or room change)
    // This ensures notifications resume when user navigates away
    // Using roomId as key ensures this disposes when:
    // 1. User navigates back (composable removed)
    // 2. User switches to a different room (roomId changes, old room's effect disposes)
    DisposableEffect(roomId) {
        // CRITICAL: Add room to opened rooms when screen opens (exempt from cache clearing on reconnect)
        RoomTimelineCache.addOpenedRoom(roomId)
        
        onDispose {
            val destinationRoute = navController.currentBackStackEntry?.destination?.route.orEmpty()
            val keepWarmForUserInfo = destinationRoute.startsWith("user_info")
            // Keep timeline warm when navigating to UserInfo so pop-back remains fluid.
            if (!keepWarmForUserInfo) {
                RoomTimelineCache.removeOpenedRoom(roomId)
            } else if (BuildConfig.DEBUG) {
                Log.d("Andromuks", "RoomTimelineScreen: Keeping room $roomId warm while in UserInfo")
            }
            // Only clear if this room is still the current room (user navigated away)
            // If user switched to a different room, the new room's LaunchedEffect will have already set currentRoomId
            if (!keepWarmForUserInfo && appViewModel.currentRoomId == roomId) {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Disposing - clearing currentRoomId for room: $roomId")
                appViewModel.clearCurrentRoomId()
            } else {
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Disposing - not clearing currentRoomId (current: ${appViewModel.currentRoomId}, this room: $roomId)")
            }
        }
    }
    
    // Track last known refresh trigger to detect when app resumes
    var lastKnownRefreshTrigger by remember { mutableStateOf(appViewModel.timelineRefreshTrigger) }
    var isInitialLoadComplete by remember(roomId) { mutableStateOf(false) }
    
    // Mark initial load as complete after a short delay to distinguish from app resume
    LaunchedEffect(roomId) {
        kotlinx.coroutines.delay(500) // Wait 500ms after room opens
        isInitialLoadComplete = true
    }
    
    // Refresh timeline when app resumes (to show new events received while suspended)
    // Only refresh if initial load is complete (not during initial room opening)
    LaunchedEffect(appViewModel.timelineRefreshTrigger) {
        if (appViewModel.timelineRefreshTrigger > 0 && 
            appViewModel.currentRoomId == roomId && 
            isInitialLoadComplete &&
            appViewModel.timelineRefreshTrigger != lastKnownRefreshTrigger) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: App resumed, refreshing timeline for room: $roomId")
            // Don't reset state flags - this is just a refresh, not a new room load
            appViewModel.requestRoomTimeline(roomId)
            lastKnownRefreshTrigger = appViewModel.timelineRefreshTrigger
        }
    }
    
    // NOTE: App resume scroll handling is now done in the app visibility LaunchedEffect above
    // This LaunchedEffect is kept for refresh trigger tracking but no longer handles scroll restoration
    LaunchedEffect(appViewModel.timelineRefreshTrigger) {
        // Update last known refresh trigger
        if (appViewModel.timelineRefreshTrigger != lastKnownRefreshTrigger) {
            lastKnownRefreshTrigger = appViewModel.timelineRefreshTrigger
        }
    }

    // Listen for foreground refresh broadcast to refresh timeline when app comes to foreground
    DisposableEffect(Unit) {
        val foregroundRefreshReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "net.vrkknn.andromuks.FOREGROUND_REFRESH") {
                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Received FOREGROUND_REFRESH broadcast, refreshing timeline UI from cache for room: $roomId")
                    // Lightweight timeline refresh from cached data (no network requests)
                    appViewModel.refreshTimelineUI()
                }
            }
        }
        
        val filter = IntentFilter("net.vrkknn.andromuks.FOREGROUND_REFRESH")
        context.registerReceiver(foregroundRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Registered FOREGROUND_REFRESH broadcast receiver")
        
        onDispose {
            try {
                context.unregisterReceiver(foregroundRefreshReceiver)
                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Unregistered FOREGROUND_REFRESH broadcast receiver")
            } catch (e: Exception) {
                Log.w("Andromuks", "RoomTimelineScreen: Error unregistering foreground refresh receiver", e)
            }
        }
    }

    // After initial batch loads, automatically load second batch in background
    // LaunchedEffect(hasLoadedInitialBatch) {
    //    if (hasLoadedInitialBatch && sortedEvents.isNotEmpty()) {
    //        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Initial batch loaded, automatically loading second batch")
    //        kotlinx.coroutines.delay(500) // Small delay to let UI settle
    //        appViewModel.loadOlderMessages(roomId)
    //    }
    // }

    // Validate and request missing user profiles when timeline events change
    // This ensures all users in the timeline have complete profile data (display name, avatar)
    // Missing profiles are automatically requested from the server
    // PERFORMANCE: Only run when app is visible and this is the current room
    LaunchedEffect(sortedEvents, appViewModel.isAppVisible, appViewModel.currentRoomId) {
        if (!appViewModel.isAppVisible || appViewModel.currentRoomId != roomId) {
            return@LaunchedEffect
        }
        
        if (sortedEvents.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Validating user profiles for ${sortedEvents.size} events"
            )
            appViewModel.validateAndRequestMissingProfiles(roomId, sortedEvents)
        }
    }

    // OPPORTUNISTIC PROFILE LOADING: Only request profiles when actually needed for rendering
    // This prevents loading 15,000+ profiles upfront for large rooms
    // PERFORMANCE: Only run when app is visible and this is the current room
    LaunchedEffect(sortedEvents, roomId, appViewModel.isAppVisible, appViewModel.currentRoomId) {
        if (!appViewModel.isAppVisible || appViewModel.currentRoomId != roomId) {
            return@LaunchedEffect
        }
        
        if (BuildConfig.DEBUG) Log.d(
            "Andromuks",
            "RoomTimelineScreen: Using opportunistic profile loading for $roomId (no bulk loading)"
        )
        
        // Only request profiles for users that are actually visible in the timeline
        // This dramatically reduces memory usage for large rooms
        if (sortedEvents.isNotEmpty()) {
            // sortedEvents is oldest-first; include both ends so senders only in recent messages
            // (e.g. first message from a user) still get profile requests without scanning all.
            val visibleUsers = buildSet {
                sortedEvents.take(50).forEach { add(it.sender) }
                sortedEvents.takeLast(50).forEach { add(it.sender) }
            }
            
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Requesting profiles on-demand for ${visibleUsers.size} visible users (instead of all ${sortedEvents.size} events)"
            )
            
            // Request profiles one by one as needed (including current user if missing)
            visibleUsers.forEach { userId ->
                // Check if profile is missing (including for current user)
                val existingProfile = appViewModel.getUserProfile(userId, roomId)
                if (existingProfile == null || existingProfile.displayName.isNullOrBlank()) {
                    appViewModel.requestUserProfileOnDemand(userId, roomId)
                }
            }
        }
    }

    // Save updated profiles to disk when member cache changes
    // This persists user profile data (display names, avatars) to disk for future app sessions
    // Only save profiles for users involved in the events being processed to avoid performance
    // issues
    // PERFORMANCE: Only run when app is visible and this is the current room
    LaunchedEffect(appViewModel.memberUpdateCounter, appViewModel.isAppVisible, appViewModel.currentRoomId) {
        if (!appViewModel.isAppVisible || appViewModel.currentRoomId != roomId) {
            return@LaunchedEffect
        }
        
        // Only save profiles for users who are actually involved in the current timeline events
        val usersInTimeline = sortedEvents.map { it.sender }.distinct().toSet()
        if (usersInTimeline.isNotEmpty()) {
            val memberMap = appViewModel.getMemberMap(roomId)
            val profilesToSave = usersInTimeline.filter { memberMap.containsKey(it) }
            if (profilesToSave.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Saving ${profilesToSave.size} profiles to disk for users in timeline"
                )
                // Profiles are cached in-memory only - no DB persistence needed
            }
        }
    }

    // Ensure timeline reactively updates when new events arrive from sync
    // OPTIMIZED: Only track timelineEvents changes directly, updateCounter is handled by receipt updates
    // PERFORMANCE: Only log when app is visible and this is the current room
    LaunchedEffect(timelineEvents, appViewModel.isAppVisible, appViewModel.currentRoomId) {
        // Only react to changes for the current room and when app is visible
        if (appViewModel.currentRoomId == roomId && appViewModel.isAppVisible) {
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Timeline events changed - timelineEvents.size: ${timelineEvents.size}, currentRoomId: ${appViewModel.currentRoomId}, roomId: $roomId")
            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Detected timeline update for current room $roomId with ${timelineEvents.size} events")
            
            // Force recomposition when timeline events change
            // This ensures the UI updates even when battery optimization might skip updates
        }
    }
    
    // CRITICAL FIX: Observe timeline changes reactively using state flows
    // This detects new events that were persisted to DB but might not have triggered timeline updates
    // (e.g., due to race conditions, timing issues, or if events weren't in sync batch)
    // This is event-driven (no polling) and only triggers when DB actually changes
    // Events are in-memory cache only - no DB observation needed
    // Timeline updates come from sync_complete and pagination

    // Handle Android back key
    BackHandler {
        if (messageMenuConfig != null) {
            // Close message menu if open
            messageMenuConfig = null
        } else if (showCameraOverlay) {
            showCameraOverlay = false
        } else if (showVideoOverlay) {
            activeVideoRecording?.stop()
            activeVideoRecording = null
            videoRecordingStartTime = null
            showVideoOverlay = false
        } else if (showAttachmentMenu) {
            // Close attachment menu if open
            showAttachmentMenu = false
        } else {
            // CRITICAL FIX: Clear currentRoomId when navigating back to ensure notifications resume
            if (appViewModel.currentRoomId == roomId) {
                appViewModel.clearCurrentRoomId()
            }
            // Pop one level: room_list → from list; settings/mentions/etc. → previous screen.
            // If this is the only destination (FCM/shortcut deep link after auth_check was popped), finish.
            if (!navController.popBackStack()) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "Andromuks",
                        "RoomTimelineScreen: popBackStack failed (single destination) — finishing activity",
                    )
                }
                context.findActivityForFinish()?.finish()
            }
        }
    }

    // Navigation bar padding - read once, not during composition
    // Note: bottomInset was removed as it's not used - .imePadding() modifier handles keyboard padding
    
    // Track scroll position before keyboard opens to preserve it
    var scrollPositionBeforeKeyboard by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (firstVisibleIndex, scrollOffset)
    var wasAttachedBeforeKeyboard by remember { mutableStateOf(false) }
    
    LaunchedEffect(
        pendingNotificationJumpEventId,
        timelineItems.size,
        readinessCheckComplete,
        appViewModel.timelineUpdateCounter,
        pendingScrollRestoration
    ) {
        val targetEventId = pendingNotificationJumpEventId ?: return@LaunchedEffect
        if (!readinessCheckComplete || timelineItems.isEmpty() || pendingScrollRestoration) {
            return@LaunchedEffect
        }
        // CRITICAL FIX: When the notification jump handler fires, timelineItems may still
        // contain the PREVIOUS room's events (or stale events).  This happens because:
        //   1. navigateToRoomWithCache is async — it launches a coroutine that processes
        //      the cache and calls buildTimelineFromChain (also async via Dispatchers.Main).
        //   2. navController.navigate fires IMMEDIATELY after, creating the new
        //      RoomTimelineScreen before the coroutine finishes.
        //   3. readinessCheckComplete becomes true quickly (old timelineEvents is non-empty),
        //      so this handler fires while timelineItems still holds old data.
        //   4. The event from the notification isn't in the old items — search fails.
        //   5. Later, when buildTimelineFromChain finishes, timelineItems is recomputed,
        //      but if its SIZE is unchanged (same number of events), this LaunchedEffect
        //      never re-fires and the event is never found.
        //
        // Fix: retry with delays to give the async pipeline time to complete:
        //   timelineEvents → sortedEvents (Dispatchers.Default) → timelineItems (Dispatchers.Default)
        val maxRetries = 8  // Up to ~2 seconds total (8 × 250ms)
        var foundIndex = -1
        for (attempt in 0..maxRetries) {
            foundIndex = timelineItems.indexOfFirst { item ->
                (item as? TimelineItem.Event)?.event?.eventId == targetEventId
            }
            if (foundIndex >= 0) break
            if (attempt < maxRetries) {
                if (BuildConfig.DEBUG) Log.d(
                    "Andromuks",
                    "RoomTimelineScreen: Notification target $targetEventId not found (attempt ${attempt + 1}/$maxRetries, timelineItems.size=${timelineItems.size}), retrying in 250ms"
                )
                kotlinx.coroutines.delay(250)
            }
        }
        if (foundIndex >= 0) {
            // Convert to reversed index: if item is at index N in original, it's at (lastIndex - N) in reversed
            val lastIndex = timelineItems.lastIndex
            val reversedIndex = lastIndex - foundIndex
            listState.scrollToItem(reversedIndex)
            // With reverseLayout, index 0 means at bottom (newest)
            isAttachedToBottom = reversedIndex == 0
            wasAttachedBeforeKeyboard = isAttachedToBottom
            hasInitialSnapCompleted = true
            hasLoadedInitialBatch = true
            pendingInitialScroll = false
            highlightedEventId = targetEventId
            highlightRequestId++
            pendingNotificationJumpEventId = null
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Jumped to notification target event=$targetEventId at original index=$foundIndex, reversed index=$reversedIndex"
            )
        } else {
            // Event truly not found after retries — likely filtered out (reaction, edit,
            // redaction, etc.) or the room was rebuilt with different events.
            // Fall back to scrolling to bottom so the user sees the latest messages.
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Notification target $targetEventId not found after $maxRetries retries (timelineItems.size=${timelineItems.size}), falling back to scroll-to-bottom"
            )
            // With reverseLayout, index 0 is bottom
            listState.scrollToItem(0)
            isAttachedToBottom = true
            wasAttachedBeforeKeyboard = true
            hasInitialSnapCompleted = true
            hasLoadedInitialBatch = true
            pendingInitialScroll = false
            pendingNotificationJumpEventId = null
        }
    }
    
    // CRITICAL FIX: Handle keyboard state changes without losing scroll position
    // PERFORMANCE: Use isKeyboardOpen derived state instead of imeBottom to reduce recomposition
    // The derived state only changes when keyboard crosses threshold, not during animation
    var previousKeyboardOpen by remember { mutableStateOf(isKeyboardOpen) }
    LaunchedEffect(isKeyboardOpen) {
        if (timelineItems.isEmpty() || listState.layoutInfo.totalItemsCount == 0) {
            previousKeyboardOpen = isKeyboardOpen
            return@LaunchedEffect
        }
        
        val keyboardJustOpened = !previousKeyboardOpen && isKeyboardOpen
        val keyboardJustClosed = previousKeyboardOpen && !isKeyboardOpen
        
        // CRITICAL: Capture scroll position BEFORE keyboard opens
        if (keyboardJustOpened) {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val scrollOffset = listState.firstVisibleItemScrollOffset
            scrollPositionBeforeKeyboard = Pair(firstVisibleIndex, scrollOffset)
            wasAttachedBeforeKeyboard = isAttachedToBottom
            
            if (BuildConfig.DEBUG) Log.d(
                "Andromuks",
                "RoomTimelineScreen: Keyboard opening - captured scroll position: index=$firstVisibleIndex, offset=$scrollOffset, attached=$isAttachedToBottom"
            )
            
            // Wait for layout to adjust after keyboard opens
            // Use a longer delay and wait for layout to actually change
            var layoutSettled = false
            val initialLayoutHeight = listState.layoutInfo.viewportSize.height
            var attempts = 0
            while (!layoutSettled && attempts < 10) {
                kotlinx.coroutines.delay(50)
                val currentLayoutHeight = listState.layoutInfo.viewportSize.height
                // Layout has changed (viewport shrunk due to keyboard)
                if (currentLayoutHeight < initialLayoutHeight - 50) {
                    layoutSettled = true
                }
                attempts++
            }
            // Additional small delay to ensure layout is fully settled
            kotlinx.coroutines.delay(50)
            
            if (timelineItems.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
                if (isAttachedToBottom) {
                    // We were attached to bottom - with reverseLayout, bottom anchor stays fixed automatically
                    // But we can explicitly scroll to 0 to ensure we're at bottom
                    coroutineScope.launch {
                        animatedScrollTo(0)
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Keyboard opened, animated scroll to bottom (index=0) after layout settled - reverseLayout")
                    }
                } else {
                    // We were NOT attached - maintain the same visible item after layout adjustment
                    // savedIndex is already in reversed coordinates (from firstVisibleItemIndex)
                    val (savedIndex, savedOffset) = scrollPositionBeforeKeyboard!!
                    val validIndex = savedIndex.coerceIn(0, timelineItems.lastIndex.coerceAtLeast(0))
                    if (validIndex >= 0) {
                        coroutineScope.launch {
                            // Try to maintain the same item visible, but adjust if needed
                            animatedScrollTo(validIndex, savedOffset)
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Keyboard opened, maintained scroll position: index=$validIndex, offset=$savedOffset after layout settled")
                        }
                    }
                }
            }
        }
        
        // CRITICAL: Restore scroll position when keyboard closes
        if (keyboardJustClosed) {
            // Wait for layout to settle after keyboard closes
            // Wait for layout to actually expand (viewport grows)
            var layoutSettled = false
            val initialLayoutHeight = listState.layoutInfo.viewportSize.height
            var attempts = 0
            while (!layoutSettled && attempts < 10) {
                kotlinx.coroutines.delay(50)
                val currentLayoutHeight = listState.layoutInfo.viewportSize.height
                // Layout has changed (viewport expanded after keyboard closed)
                if (currentLayoutHeight > initialLayoutHeight + 50) {
                    layoutSettled = true
                }
                attempts++
            }
            // Additional small delay to ensure layout is fully settled
            kotlinx.coroutines.delay(50)
            
            if (timelineItems.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
                if (wasAttachedBeforeKeyboard) {
                    // We were attached to bottom - with reverseLayout, index 0 is bottom
                    coroutineScope.launch {
                        animatedScrollTo(0)
                        isAttachedToBottom = true
                        if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Keyboard closed, animated scroll to bottom (index=0) - reverseLayout")
                    }
                } else if (scrollPositionBeforeKeyboard != null) {
                    // We were NOT attached to bottom, restore exact scroll position
                    // savedIndex is already in reversed coordinates
                    val (savedIndex, savedOffset) = scrollPositionBeforeKeyboard!!
                    // Clamp index to valid range
                    val validIndex = savedIndex.coerceIn(0, timelineItems.lastIndex.coerceAtLeast(0))
                    if (validIndex >= 0) {
                        coroutineScope.launch {
                            animatedScrollTo(validIndex, savedOffset)
                            isAttachedToBottom = false // Keep detached state
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Keyboard closed, restored scroll position: index=$validIndex, offset=$savedOffset (was NOT attached)")
                        }
                    }
                    scrollPositionBeforeKeyboard = null
                }
            }
        }
        
        previousKeyboardOpen = isKeyboardOpen
    }

    CompositionLocalProvider(
        LocalScrollHighlightState provides ScrollHighlightState(
            eventId = highlightedEventId,
            requestId = highlightRequestId
        ),
        LocalActiveMessageMenuEventId provides messageMenuConfig?.event?.eventId,
        net.vrkknn.andromuks.ui.components.LocalIsScrollingFast provides isAnimatedScrolling
    ) {
        AndromuksTheme {
            Surface {
                Box(modifier = modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .imePadding()  // Handle keyboard padding at Column level
                            .then(
                                if (showDeleteDialog) {
                                    Modifier.blur(10.dp)
                                } else {
                                    Modifier
                                }
                            )
                ) {
                    // 1. Room Header (always visible at the top, below status bar)
                    RoomHeader(
                        roomState = currentRoomState,
                        fallbackName = displayRoomName,
                        fallbackAvatarUrl = displayAvatarUrl,
                        homeserverUrl = appViewModel.homeserverUrl,
                        authToken = appViewModel.authToken,
                        roomId = roomId,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onHeaderClick = {
                            // Navigate to room info screen
                            navController.navigate("room_info/$roomId")
                        },
                        onBackClick = {
                            if (appViewModel.currentRoomId == roomId) {
                                appViewModel.clearCurrentRoomId()
                            }
                            if (!navController.popBackStack()) {
                                context.findActivityForFinish()?.finish()
                            }
                        },
                        onCallClick = {
                            navController.navigate("element_call/$roomId")
                        },
                        onRefreshClick = {
                            // Full refresh: drop all on-disk and in-RAM data, then fetch 100 events
                            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Full refresh button clicked for room $roomId")
                            isRefreshing = true
                            appViewModel.setAutoPaginationEnabled(false, "manual_refresh_ui_$roomId")
                            appViewModel.fullRefreshRoomTimeline(roomId)
                        }
                    )

                    if (appViewModel.notificationActionInProgress) {
                        ExpressiveStatusRow(
                            text = "Completing notification action...",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                        )
                    }
                    
                    // Show upload status when uploads are in progress
                    if (appViewModel.hasUploadInProgress(roomId)) {
                        val uploadType = appViewModel.getUploadType(roomId)
                        val retryCount = appViewModel.getUploadRetryCount(roomId)
                        val retrySuffix = if (retryCount > 0) " (Retrying $retryCount/3)" else ""
                        val statusText = when (uploadType) {
                            "video" -> "Uploading video$retrySuffix..."
                            "audio" -> "Uploading audio$retrySuffix..."
                            "file" -> "Uploading file$retrySuffix..."
                            "image" -> "Uploading image$retrySuffix..."
                            else -> "Uploading media$retrySuffix..."
                        }
                        val uploadProgress = appViewModel.getUploadProgress(roomId)
                        ExpressiveStatusRow(
                            text = statusText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                            progress = uploadProgress
                        )
                    }

                    // 2. Timeline (compressible, scrollable content)
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .then(
                                if (showAttachmentMenu || messageMenuConfig != null) {
                                    Modifier.clickable {
                                        // Close attachment menu or message menu when tapping outside
                                        showAttachmentMenu = false
                                        messageMenuConfig = null
                                    }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        // CRITICAL FIX: Show "Room loading..." while room is being loaded/processed
                        // This ensures the UI doesn't show incomplete state when navigating to a room
                        // Show loading when: isLoading is true OR timeline is empty and we're waiting for initial load
        val shouldShowLoading = !readinessCheckComplete || isLoading || (timelineItems.isEmpty() && !hasInitialSnapCompleted)
                        
                        if (shouldShowLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    ExpressiveLoadingIndicator(modifier = Modifier.size(96.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Room loading...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            // When media async-load changes height (portrait E2EE), re-anchor scroll if at bottom
                            DisposableEffect(listState, coroutineScope) {
                                TimelineMediaLayoutCallback.callback = {
                                    coroutineScope.launch {
                                        if (pendingScrollRestoration) return@launch
                                        if (listState.firstVisibleItemIndex == 0 &&
                                            listState.firstVisibleItemScrollOffset < 100
                                        ) {
                                            animatedScrollTo(0)
                                        }
                                    }
                                }
                                onDispose { TimelineMediaLayoutCallback.callback = null }
                            }
                            Box(modifier = Modifier.fillMaxSize()) {
                            // PERF: Memoize reversed list — .reversed() allocates a new list on every recomposition.
                            // Only recompute when timelineItems itself changes.
                            val reversedTimelineItems = remember(timelineItems) { timelineItems.reversed() }

                            LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pullRefresh(pullRefreshState),
                                state = listState,
                            // CRITICAL: Use reverseLayout to anchor list at bottom (like WhatsApp/Google Messages)
                            // This makes keyboard handling automatic - viewport shrinks but bottom anchor stays fixed
                            reverseLayout = true,
                            // PERFORMANCE: Optimize for timeline rendering with proper padding and settings
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 8.dp,
                                end = 0.dp,
                                top = 8.dp,
                                bottom = 120.dp // Extra padding at bottom for better scroll performance
                            ),
                            // PERFORMANCE: Enable smooth scrolling optimizations
                            userScrollEnabled = true
                        ) {
                            // Show loading indicator at the top when paginating
                            if (appViewModel.isPaginating) {
                                item(key = "loading_indicator") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ExpressiveLoadingIndicator(
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            // PERFORMANCE: Use stable keys and pre-computed consecutive flags
                            // CRITICAL: Reverse items list since reverseLayout flips rendering order but not data order
                            itemsIndexed(
                                items = reversedTimelineItems,
                                key = { _, item -> item.stableKey }
                            ) { index, item ->
                                when (item) {
                                    is TimelineItem.DateDivider -> {
                                        DateDivider(item.date)
                                    }
                                    is TimelineItem.Event -> {
                                        val event = item.event
                                        // PERFORMANCE: Removed logging from item rendering to improve scroll performance
                                        // Note: myUserId is non-null String, so myUserId != null check is redundant
                                        val isMine = event.sender == myUserId

                                        // PERFORMANCE: Use pre-computed consecutive flag instead of index-based lookup
                                        val isConsecutive = item.isConsecutive

                                        // Add a little extra spacing before non-consecutive messages
                                        // (only when the previous timeline item is also an event).
                                        val previousItem = if (index > 0) timelineItems[index - 1] else null
                                        val addTopSpacing =
                                            previousItem is TimelineItem.Event && !isConsecutive

                                        val threadInfo = event.getThreadInfo()
                                        if (threadInfo != null) {
                                            if (BuildConfig.DEBUG) Log.d(
                                                "Andromuks",
                                                "RoomTimelineScreen: Rendering thread event ${event.eventId} -> root=${threadInfo.threadRootEventId}, fallbackReply=${threadInfo.fallbackReplyToEventId ?: "<none>"}"
                                            )
                                        } else if (
                                            event.content?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.thread" ||
                                            event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type") == "m.thread"
                                        ) {
                                            val relationType = event.relationType
                                            val relatesToId = event.relatesTo
                                            val rawRelType = event.content?.optJSONObject("m.relates_to")?.optString("rel_type")
                                            val decryptedRelType = event.decrypted?.optJSONObject("m.relates_to")?.optString("rel_type")
                                            val rawEventId = event.content?.optJSONObject("m.relates_to")?.optString("event_id")
                                            val decryptedEventId = event.decrypted?.optJSONObject("m.relates_to")?.optString("event_id")
                                            Log.w(
                                                "Andromuks",
                                                "RoomTimelineScreen: Event ${event.eventId} has thread relates_to but getThreadInfo() returned null (relationType=$relationType, relatesTo=$relatesToId, rawRelType=$rawRelType, decryptedRelType=$decryptedRelType, rawEventId=$rawEventId, decryptedEventId=$decryptedEventId)"
                                            )
                                        }

                                        val threadRootIdFromRelates = event.content?.optJSONObject("m.relates_to")?.optString("event_id")
                                            ?: event.decrypted?.optJSONObject("m.relates_to")?.optString("event_id")
                                        if (threadRootIdFromRelates != null && threadInfo == null) {
                                            if (BuildConfig.DEBUG) Log.d(
                                                "Andromuks",
                                                "RoomTimelineScreen: Event ${event.eventId} relates_to thread root $threadRootIdFromRelates but threadInfo is null"
                                            )
                                        }

                                        Column {
                                            if (addTopSpacing) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                            }

                                            TimelineEventItem(
                                                event = event,
                                                timelineEvents = timelineEvents,
                                                homeserverUrl = homeserverUrl,
                                                authToken = authToken,
                                                userProfileCache = memberMapWithFallback,
                                                isMine = isMine,
                                                myUserId = myUserId,
                                                isConsecutive = isConsecutive,
                                                appViewModel = appViewModel,
                                                sharedTransitionScope = sharedTransitionScope,  // ← ADD THIS
                                                animatedVisibilityScope = animatedVisibilityScope,  // ← ADD THIS
                                                onScrollToMessage = { eventId ->
                                                // PERFORMANCE: Find the index in timelineItems instead of sortedEvents
                                                val indexInOriginal = timelineItems.indexOfFirst { item ->
                                                    when (item) {
                                                        is TimelineItem.Event -> item.event.eventId == eventId
                                                        is TimelineItem.DateDivider -> false
                                                    }
                                                }
                                                if (indexInOriginal >= 0) {
                                                    // Convert to reversed index: if item is at index N in original, it's at (lastIndex - N) in reversed
                                                    val lastIndex = timelineItems.lastIndex
                                                    val reversedIndex = lastIndex - indexInOriginal
                                                    coroutineScope.launch {
                                                        listState.scrollToItem(reversedIndex)
                                                        highlightedEventId = eventId
                                                        highlightRequestId++
                                                    }
                                                } else {
                                                    val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                                    val encodedEventId = java.net.URLEncoder.encode(eventId, "UTF-8")
                                                    navController.navigate("event_context/$encodedRoomId/$encodedEventId")
                                                }
                                                },
                                                onReply = { event -> replyingToEvent = event },
                                                onReact = { event ->
                                                reactingToEvent = event
                                                showEmojiSelection = true
                                                },
                                                onEdit = { event -> editingEvent = event },
                                                onDelete = { event ->
                                                deletingEvent = event
                                                showDeleteDialog = true
                                                },
                                                onUserAvatarClick = { userId, tappedEventId ->
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: onUserAvatarClick -> userId=$userId, tappedEventId=$tappedEventId")
                                                navController.navigateToUserInfo(userId, roomId, tappedEventId)
                                                },
                                                onUserClick = { userId ->
                                                // Pass eventId for shared transition animation
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Navigating to user info - userId=$userId, eventId=${event.eventId}")
                                                navController.navigateToUserInfo(userId, roomId, event.eventId)
                                                },
                                                onRoomLinkClick = { roomLink ->
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Room link clicked: ${roomLink.roomIdOrAlias}")
                                                
                                                // Extract server from message sender (format: @user:server.com)
                                                val senderServer = try {
                                                    if (event.sender.contains(":")) {
                                                        event.sender.substringAfter(":")
                                                    } else {
                                                        null
                                                    }
                                                } catch (e: Exception) {
                                                    null
                                                }
                                                
                                                // Add sender's server to viaServers if available
                                                val enhancedViaServers = if (senderServer != null && !roomLink.viaServers.contains(senderServer)) {
                                                    roomLink.viaServers + senderServer
                                                } else {
                                                    roomLink.viaServers
                                                }
                                                
                                                val enhancedRoomLink = roomLink.copy(viaServers = enhancedViaServers)
                                                
                                                // If it's a room ID, check if we're already joined
                                                val existingRoom = if (enhancedRoomLink.roomIdOrAlias.startsWith("!")) {
                                                    val room = appViewModel.getRoomById(enhancedRoomLink.roomIdOrAlias)
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Checked for existing room ${enhancedRoomLink.roomIdOrAlias}, found: ${room != null}")
                                                    room
                                                } else {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Room link is an alias, showing joiner")
                                                    null
                                                }
                                                
                                                if (existingRoom != null) {
                                                    // Already joined, navigate directly
                                                    val targetRoomId = enhancedRoomLink.roomIdOrAlias
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Already joined, navigating to $targetRoomId")
                                                    // CRITICAL: When navigating from one room_timeline to another, use setDirectRoomNavigation
                                                    // and navigate via room_list, letting RoomListScreen handle the final navigation.
                                                    // This matches the pattern used by notifications/shortcuts and ensures proper state management.
                                                    appViewModel.setCurrentRoomIdForTimeline(targetRoomId)
                                                    appViewModel.setDirectRoomNavigation(targetRoomId)
                                                    navController.navigate("room_list")
                                                } else {
                                                    // For aliases or non-joined rooms, show room joiner
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Not joined, showing room joiner with via servers: $enhancedViaServers")
                                                    roomLinkToJoin = enhancedRoomLink
                                                    showRoomJoiner = true
                                                }
                                                },
                                                onThreadClick = { threadEvent ->
                                                // Navigate to thread viewer
                                                val threadInfo = threadEvent.getThreadInfo()
                                                if (threadInfo != null) {
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Thread message clicked, opening thread for root: ${threadInfo.threadRootEventId}")
                                                    val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                                    val encodedThreadRoot = java.net.URLEncoder.encode(threadInfo.threadRootEventId, "UTF-8")
                                                    appViewModel.threadReturnScrollEventId = threadEvent.eventId
                                                    if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Set threadReturnScrollEventId=${threadEvent.eventId}")
                                                    navController.navigate("thread_viewer/$encodedRoomId/$encodedThreadRoot")
                                                }
                                                },
                                                onCodeBlockClick = { code ->
                                                codeViewerContent = code
                                                showCodeViewer = true
                                                },
                                                onShowMenu = { menuConfig ->
                                                // Close attach menu if open
                                                showAttachmentMenu = false
                                                messageMenuConfig = menuConfig.copy(
                                                    onViewSource = { code ->
                                                        codeViewerContent = code
                                                        showCodeViewer = true
                                                    },
                                                    onViewRenderedText = { text ->
                                                        codeViewerContent = text
                                                        showCodeViewer = true
                                                    },
                                                    onShowReactions = {
                                                        // Backfill detailed reactions for this event (user + timestamp) via get_related_events.
                                                        appViewModel.requestReactionDetails(roomId, menuConfig.event.eventId)
                                                        reactionsEventId = menuConfig.event.eventId
                                                        showReactionsDialog = true
                                                    },
                                                    onViewInThread = if (menuConfig.event.isThreadMessage()) {
                                                        {
                                                            val threadInfo = menuConfig.event.getThreadInfo()
                                                            if (threadInfo != null) {
                                                                val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
                                                                val encodedThreadRoot = java.net.URLEncoder.encode(threadInfo.threadRootEventId, "UTF-8")
                                                                appViewModel.threadReturnScrollEventId = menuConfig.event.eventId
                                                                navController.navigate("thread_viewer/$encodedRoomId/$encodedThreadRoot")
                                                            }
                                                        }
                                                    } else null,
                                                    onShowBridgeDeliveryInfo = if (appViewModel.messageBridgeSendStatus.containsKey(menuConfig.event.eventId)) {
                                                        {
                                                            bridgeDeliveryEventId = menuConfig.event.eventId
                                                            showBridgeDeliveryDialog = true
                                                        }
                                                    } else null
                                                )
                                                },
                                                onShowReactions = {
                                                    // Direct reactions button (without opening the full menu)
                                                    appViewModel.requestReactionDetails(roomId, event.eventId)
                                                    reactionsEventId = event.eventId
                                                    showReactionsDialog = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                            
                            // Pull-to-refresh indicator (outside LazyColumn, inside Box)
                            PullRefreshIndicator(
                                refreshing = isRefreshingPull,
                                state = pullRefreshState,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    }
                    }
                    
                    // 4. Typing notification area (stacks naturally above text box)
                    TypingNotificationArea(
                        typingUsers = appViewModel.getTypingUsersForRoom(roomId),
                        roomId = roomId,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        userProfileCache = appViewModel.getMemberMap(roomId),
                        appViewModel = appViewModel
                    )

                    // 5. Text box (always at the bottom, above keyboard/nav bar)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier =
                            Modifier.fillMaxWidth()
                                .navigationBarsPadding()
                                // .imePadding() removed - Column handles it now
                    ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Main attach button
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.width(48.dp).height(buttonHeight)
                        ) {
                            IconButton(
                                enabled = isInputEnabled,
                                onClick = { 
                                    if (isInputEnabled) {
                                        // Close message menu if open
                                        messageMenuConfig = null
                                        showAttachmentMenu = !showAttachmentMenu
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AttachFile,
                                    contentDescription = "Attach",
                                    tint = if (isInputEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Pill-shaped text input with optional reply preview inside
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape =
                                RoundedCornerShape(
                                    16.dp
                                ), // Rounded rectangle that works both as pill and expanded
                            tonalElevation = 1.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column {
                                // Edit preview inside the text input (if editing)
                                if (editingEvent != null) {
                                    EditPreviewInput(
                                        event = editingEvent!!,
                                        onCancel = {
                                            editingEvent = null
                                            draft = "" // Clear draft when canceling edit
                                        }
                                    )
                                }

                                // Reply preview inside the text input (if replying)
                                if (replyingToEvent != null) {
                                    ReplyPreviewInput(
                                        event = replyingToEvent!!,
                                        userProfileCache = memberMapWithFallback, // Use reactive memberMap with fallback profiles
                                        onCancel = { replyingToEvent = null },
                                        appViewModel = appViewModel,
                                        roomId = roomId
                                    )
                                }

                                // Create combined transformation for mentions and custom emojis
                                val colorScheme = MaterialTheme.colorScheme
                                val customEmojiPacks = appViewModel.customEmojiPacks
                                val mentionAndEmojiTransformation = remember(colorScheme, customEmojiPacks) {
                                    VisualTransformation { text ->
                                        val mentionRegex = Regex("""\[((?:[^\[\]\\]|\\.)*)\]\(https://matrix\.to/#/([^)]+)\)""")
                                        // Regex for custom emoji markdown: ![:name:](mxc://url "Emoji: :name:")
                                        val customEmojiRegex = Regex("""!\[:([^:]+):\]\((mxc://[^)]+)\s+"[^"]*"\)""")
                                        
                                        val annotatedString = buildAnnotatedString {
                                            var lastIndex = 0
                                            
                                            // Collect all matches (mentions and custom emojis) and sort by position
                                            val allMatches = mutableListOf<Pair<Int, MatchResult>>()
                                            mentionRegex.findAll(text.text).forEach { 
                                                allMatches.add(Pair(0, it)) // 0 = mention
                                            }
                                            customEmojiRegex.findAll(text.text).forEach { 
                                                allMatches.add(Pair(1, it)) // 1 = custom emoji
                                            }
                                            allMatches.sortBy { it.second.range.first }
                                            
                                            for ((type, match) in allMatches) {
                                                // Add text before match
                                                if (match.range.first > lastIndex) {
                                                    append(text.text.substring(lastIndex, match.range.first))
                                                }
                                                
                                                if (type == 0) {
                                                    // Handle mention
                                                    val escapedDisplayName = match.groupValues[1]
                                                    val displayName = escapedDisplayName
                                                        .replace("\\[", "[")
                                                        .replace("\\]", "]")
                                                    withStyle(
                                                        style = SpanStyle(
                                                            color = colorScheme.onPrimaryContainer,
                                                            background = colorScheme.primaryContainer
                                                        )
                                                    ) {
                                                        append(" $displayName ")
                                                    }
                                                } else {
                                                    // Handle custom emoji - replace markdown with just the emoji name
                                                    val emojiName = match.groupValues[1]
                                                    append(":$emojiName:")
                                                }
                                                
                                                lastIndex = match.range.last + 1
                                            }
                                            
                                            // Add remaining text
                                            if (lastIndex < text.text.length) {
                                                append(text.text.substring(lastIndex))
                                            }
                                        }
                                        
                                        // Create proper offset mapping to handle the text length changes
                                        val offsetMapping = object : OffsetMapping {
                                            override fun originalToTransformed(offset: Int): Int {
                                                // Clamp offset to valid range
                                                val clampedOffset = offset.coerceIn(0, text.text.length)
                                                var transformedOffset = 0
                                                var originalOffset = 0
                                                
                                                for (match in mentionRegex.findAll(text.text)) {
                                                    // Add text before mention
                                                    val beforeLength = match.range.first - originalOffset
                                                    if (clampedOffset <= match.range.first) {
                                                        val result = transformedOffset + (clampedOffset - originalOffset)
                                                        return result.coerceIn(0, annotatedString.length)
                                                    }
                                                    transformedOffset += beforeLength
                                                    originalOffset = match.range.first
                                                    
                                                    // Handle mention transformation
                                                    val escapedDisplayName = match.groupValues[1]
                                                    val displayName = escapedDisplayName
                                                        .replace("\\[", "[")
                                                        .replace("\\]", "]")
                                                    val transformedMentionLength = " $displayName ".length
                                                    
                                                    if (clampedOffset <= match.range.last + 1) {
                                                        val result = transformedOffset + transformedMentionLength
                                                        return result.coerceIn(0, annotatedString.length)
                                                    }
                                                    
                                                    transformedOffset += transformedMentionLength
                                                    originalOffset = match.range.last + 1
                                                }
                                                
                                                // Handle remaining text
                                                val result = transformedOffset + (clampedOffset - originalOffset)
                                                return result.coerceIn(0, annotatedString.length)
                                            }
                                            
                                            override fun transformedToOriginal(offset: Int): Int {
                                                // Clamp offset to valid range
                                                val clampedOffset = offset.coerceIn(0, annotatedString.length)
                                                var transformedOffset = 0
                                                var originalOffset = 0
                                                
                                                for (match in mentionRegex.findAll(text.text)) {
                                                    val beforeLength = match.range.first - originalOffset
                                                    if (clampedOffset <= transformedOffset + beforeLength) {
                                                        val result = originalOffset + (clampedOffset - transformedOffset)
                                                        return result.coerceIn(0, text.text.length)
                                                    }
                                                    transformedOffset += beforeLength
                                                    originalOffset = match.range.first
                                                    
                                                    val escapedDisplayName = match.groupValues[1]
                                                    val displayName = escapedDisplayName
                                                        .replace("\\[", "[")
                                                        .replace("\\]", "]")
                                                    val transformedMentionLength = " $displayName ".length
                                                    
                                                    if (clampedOffset <= transformedOffset + transformedMentionLength) {
                                                        return match.range.last + 1
                                                    }
                                                    
                                                    transformedOffset += transformedMentionLength
                                                    originalOffset = match.range.last + 1
                                                }
                                                
                                                val result = originalOffset + (clampedOffset - transformedOffset)
                                                return result.coerceIn(0, text.text.length)
                                            }
                                        }
                                        
                                        TransformedText(
                                            annotatedString,
                                            offsetMapping
                                        )
                                    }
                                }

                                // Text input field with mention support
                                CustomBubbleTextField(
                                    value = textFieldValue,
                                    enabled = isInputEnabled,
                                    onValueChange = { newValue: TextFieldValue ->
                                        if (!isInputEnabled) return@CustomBubbleTextField
                                        // First, handle custom emoji deletion (backspace on :name:)
                                        val afterDeletion = handleCustomEmojiDeletion(textFieldValue, newValue)
                                        
                                        // Then, apply any completed :shortcode: replacement
                                        val replacedValue = applyCompletedEmojiShortcode(afterDeletion)
                                        textFieldValue = replacedValue
                                        draft = replacedValue.text
                                        
                                        // Detect commands first ( /command ) - check before everything else
                                        val commandResult = detectCommand(
                                            replacedValue.text,
                                            replacedValue.selection.start
                                        )
                                        if (commandResult != null) {
                                            val (query, startIndex) = commandResult
                                            commandQuery = query
                                            commandStartIndex = startIndex
                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: / detected, query='$query'")
                                            showCommandSuggestionList = true
                                            // Hide other suggestion lists when command is active
                                            showMentionList = false
                                            showEmojiSuggestionList = false
                                            showRoomSuggestionList = false
                                        } else {
                                            showCommandSuggestionList = false
                                            
                                            // Detect room mentions ( #roomalias ) - check before mentions/emojis
                                            val roomResult = detectRoomMention(
                                                replacedValue.text,
                                                replacedValue.selection.start
                                            )
                                            if (roomResult != null) {
                                                val (query, startIndex) = roomResult
                                                roomQuery = query
                                                roomStartIndex = startIndex
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: # detected, query='$query', roomsWithAliases.size=${roomsWithAliases.size}")
                                                showRoomSuggestionList = true
                                                // Hide other suggestion lists when room mention is active
                                                showMentionList = false
                                                showEmojiSuggestionList = false
                                            } else {
                                                showRoomSuggestionList = false
                                                
                                                // Detect mentions
                                                val mentionResult = detectMention(
                                                    replacedValue.text,
                                                    replacedValue.selection.start
                                                )
                                                if (mentionResult != null) {
                                                    val (query, startIndex) = mentionResult
                                                    mentionQuery = query
                                                    mentionStartIndex = startIndex
                                                    
                                                    // CRITICAL FIX: Load cached members immediately, then request fresh data
                                                    if (!isWaitingForFullMemberList && !showMentionList) {
                                                        // Check if we already have members in memory cache
                                                        val memberMap = appViewModel.getMemberMap(roomId)
                                                        if (memberMap.isEmpty() || memberMap.size < 10) {
                                                            // Profiles are loaded opportunistically when rendering events
                                                            // Request full member list to populate cache
                                                            // Request fresh data from server (will update when it arrives)
                                                            if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: @ detected, requesting fresh member list for room $roomId")
                                                            isWaitingForFullMemberList = true
                                                            lastMemberUpdateCounterBeforeMention = appViewModel.memberUpdateCounter
                                                            appViewModel.requestFullMemberList(roomId)
                                                        } else {
                                                            // We already have members in memory, show list immediately
                                                            showMentionList = true
                                                        }
                                                    }
                                                    // Hide other suggestion lists when mention is active
                                                    showEmojiSuggestionList = false
                                                } else {
                                                    showMentionList = false
                                                    isWaitingForFullMemberList = false
                                                    
                                                    // Detect emoji shortcodes ( :shortname )
                                                    val emojiResult = detectEmojiShortcode(
                                                        replacedValue.text,
                                                        replacedValue.selection.start
                                                    )
                                                    if (emojiResult != null) {
                                                        val (query, startIndex) = emojiResult
                                                        emojiQuery = query
                                                        emojiStartIndex = startIndex
                                                        showEmojiSuggestionList = true
                                                    } else {
                                                        showEmojiSuggestionList = false
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    placeholder = {
                                        Text(
                                            text = when {
                                                !canSendMessage -> "You don't have permission to send messages"
                                                isProcessingBatch -> if (processingBatchSize > 0) "Rushing $processingBatchSize messages..." else "Rushing messages..."
                                                else -> {
                                                    val networkName = currentRoomState?.bridgeInfo?.displayName
                                                    if (networkName != null && networkName.isNotBlank()) {
                                                        "Type a $networkName message..."
                                                    } else {
                                                        "Type a message..."
                                                    }
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontStyle = FontStyle.Italic,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(textFieldFocusRequester),
                                    minLines = 1,
                                    maxLines = 5,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                                    onHeightChanged = { height ->
                                        // Only update if text is empty or single-line (to get the minimum height)
                                        val lineCount = draft.lines().size.coerceAtLeast(1)
                                        if (lineCount == 1 && (textFieldHeight == 0 || height < textFieldHeight)) {
                                            textFieldHeight = height
                                        }
                                    },
                                    trailingIcon = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Sticker button
                                            IconButton(
                                                enabled = isInputEnabled,
                                                onClick = { if (isInputEnabled) showStickerPickerForText = true },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                @Suppress("DEPRECATION")
                                                Icon(
                                                    imageVector = Icons.Outlined.StickyNote2,
                                                    contentDescription = "Stickers",
                                                    tint = if (isInputEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                                )
                                            }
                                            // Emoji button
                                            IconButton(
                                                enabled = isInputEnabled,
                                                onClick = { if (isInputEnabled) showEmojiPickerForText = true },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Mood,
                                                    contentDescription = "Emoji",
                                                    tint = if (isInputEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                                )
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Sentences,
                                        keyboardType = KeyboardType.Text,
                                        autoCorrectEnabled = true,
                                        imeAction = ImeAction.Default // Enter always creates newline, send button always sends
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (!isInputEnabled) {
                                                android.widget.Toast.makeText(
                                                context,
                                                when {
                                                    isProcessingBatch -> "Catching up on messages..."
                                                    else -> "You don't have permission to send messages"
                                                },
                                                android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                return@KeyboardActions
                                            }
                                            if (draft.isNotBlank()) {
                                                // Check if this is a command first
                                                val isCommand = appViewModel.executeCommand(roomId, draft, context, navController)
                                                if (isCommand) {
                                                    // Command was executed, clear draft
                                                    draft = ""
                                                    textFieldValue = TextFieldValue("")
                                                    return@KeyboardActions
                                                } else if (draft.trim().startsWith("/")) {
                                                    // Check if it's an avatar command that needs image picker
                                                    val command = draft.trim().lowercase()
                                                    when {
                                                        command == "/myroomavatar" || command == "/myroomavatar " -> {
                                                            pendingAvatarCommand = "myroomavatar"
                                                            if (needsMediaPermissions() && !hasRequiredMediaPermissions("image")) {
                                                                pendingMediaPickerType = "image"
                                                                mediaPermissionLauncher.launch(arrayOf(
                                                                    Manifest.permission.READ_MEDIA_IMAGES,
                                                                    Manifest.permission.READ_MEDIA_VIDEO
                                                                ))
                                                            } else {
                                                                avatarImagePickerLauncher.launch("image/*")
                                                            }
                                                            draft = ""
                                                            textFieldValue = TextFieldValue("")
                                                            return@KeyboardActions
                                                        }
                                                        command == "/globalavatar" || command == "/globalavatar " -> {
                                                            pendingAvatarCommand = "globalavatar"
                                                            if (needsMediaPermissions() && !hasRequiredMediaPermissions("image")) {
                                                                pendingMediaPickerType = "image"
                                                                mediaPermissionLauncher.launch(arrayOf(
                                                                    Manifest.permission.READ_MEDIA_IMAGES,
                                                                    Manifest.permission.READ_MEDIA_VIDEO
                                                                ))
                                                            } else {
                                                                avatarImagePickerLauncher.launch("image/*")
                                                            }
                                                            draft = ""
                                                            textFieldValue = TextFieldValue("")
                                                            return@KeyboardActions
                                                        }
                                                        command == "/roomavatar" || command == "/roomavatar " -> {
                                                            pendingAvatarCommand = "roomavatar"
                                                            if (needsMediaPermissions() && !hasRequiredMediaPermissions("image")) {
                                                                pendingMediaPickerType = "image"
                                                                mediaPermissionLauncher.launch(arrayOf(
                                                                    Manifest.permission.READ_MEDIA_IMAGES,
                                                                    Manifest.permission.READ_MEDIA_VIDEO
                                                                ))
                                                            } else {
                                                                avatarImagePickerLauncher.launch("image/*")
                                                            }
                                                            draft = ""
                                                            textFieldValue = TextFieldValue("")
                                                            return@KeyboardActions
                                                        }
                                                    }
                                                }
                                                
                                                // Send edit if editing a message
                                                if (editingEvent != null) {
                                                    appViewModel.sendEdit(roomId, draft, editingEvent!!)
                                                    editingEvent = null // Clear edit state
                                                }
                                                // Send reply if replying to a message
                                                else if (replyingToEvent != null) {
                                                    // Check if replying to a thread message
                                                    val threadInfo = replyingToEvent!!.getThreadInfo()
                                                    if (threadInfo != null) {
                                                        // Send thread reply
                                                        appViewModel.sendThreadReply(
                                                            roomId = roomId,
                                                            text = draft,
                                                            threadRootEventId = threadInfo.threadRootEventId,
                                                            fallbackReplyToEventId = replyingToEvent!!.eventId
                                                        )
                                                    } else {
                                                        // Send normal reply
                                                        appViewModel.sendReply(roomId, draft, replyingToEvent!!)
                                                    }
                                                    replyingToEvent = null // Clear reply state
                                                    messageSoundPlayer.play() // Play sound when sending reply
                                                }
                                                // Otherwise send regular message
                                                else {
                                                    appViewModel.sendMessage(roomId, draft)
                                                    messageSoundPlayer.play() // Play sound when sending message
                                                }
                                                draft = "" // Clear the input after sending
                                            }
                                        }
                                    ),
                                    visualTransformation = mentionAndEmojiTransformation
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Show expressive indicator when an upload is in progress.
                        // Message sends use local echo in the timeline instead of a button spinner.
                        val showSendIndicator = isUploading
                        
                        Button(
                            onClick = {
                                if (!isInputEnabled) {
                                    android.widget.Toast.makeText(
                                        context,
                                        when {
                                            isProcessingBatch -> "Catching up on messages..."
                                            else -> "You don't have permission to send messages"
                                        },
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }
                                if (draft.isNotBlank()) {
                                    // Check if this is a command first
                                    val isCommand = appViewModel.executeCommand(roomId, draft, context, navController)
                                    if (isCommand) {
                                        // Command was executed, clear draft
                                        draft = ""
                                        textFieldValue = TextFieldValue("")
                                        return@Button
                                    } else if (draft.trim().startsWith("/")) {
                                        // Check if it's an avatar command that needs image picker
                                        val command = draft.trim().lowercase()
                                        when {
                                            command == "/myroomavatar" || command == "/myroomavatar " -> {
                                                pendingAvatarCommand = "myroomavatar"
                                                if (needsMediaPermissions() && !hasRequiredMediaPermissions("image")) {
                                                    pendingMediaPickerType = "image"
                                                    mediaPermissionLauncher.launch(arrayOf(
                                                        Manifest.permission.READ_MEDIA_IMAGES,
                                                        Manifest.permission.READ_MEDIA_VIDEO
                                                    ))
                                                } else {
                                                    avatarImagePickerLauncher.launch("image/*")
                                                }
                                                draft = ""
                                                textFieldValue = TextFieldValue("")
                                                return@Button
                                            }
                                            command == "/globalavatar" || command == "/globalavatar " -> {
                                                pendingAvatarCommand = "globalavatar"
                                                if (needsMediaPermissions() && !hasRequiredMediaPermissions("image")) {
                                                    pendingMediaPickerType = "image"
                                                    mediaPermissionLauncher.launch(arrayOf(
                                                        Manifest.permission.READ_MEDIA_IMAGES,
                                                        Manifest.permission.READ_MEDIA_VIDEO
                                                    ))
                                                } else {
                                                    avatarImagePickerLauncher.launch("image/*")
                                                }
                                                draft = ""
                                                textFieldValue = TextFieldValue("")
                                                return@Button
                                            }
                                            command == "/roomavatar" || command == "/roomavatar " -> {
                                                pendingAvatarCommand = "roomavatar"
                                                if (needsMediaPermissions() && !hasRequiredMediaPermissions("image")) {
                                                    pendingMediaPickerType = "image"
                                                    mediaPermissionLauncher.launch(arrayOf(
                                                        Manifest.permission.READ_MEDIA_IMAGES,
                                                        Manifest.permission.READ_MEDIA_VIDEO
                                                    ))
                                                } else {
                                                    avatarImagePickerLauncher.launch("image/*")
                                                }
                                                draft = ""
                                                textFieldValue = TextFieldValue("")
                                                return@Button
                                            }
                                        }
                                    }
                                    
                                    // Send edit if editing a message
                                    if (editingEvent != null) {
                                        appViewModel.sendEdit(roomId, draft, editingEvent!!)
                                        editingEvent = null // Clear edit state
                                    }
                                    // Send reply if replying to a message
                                    else if (replyingToEvent != null) {
                                        // Check if replying to a thread message
                                        val threadInfo = replyingToEvent!!.getThreadInfo()
                                        if (threadInfo != null) {
                                            // Send thread reply
                                            appViewModel.sendThreadReply(
                                                roomId = roomId,
                                                text = draft,
                                                threadRootEventId = threadInfo.threadRootEventId,
                                                fallbackReplyToEventId = replyingToEvent!!.eventId
                                            )
                                        } else {
                                            // Send normal reply
                                            appViewModel.sendReply(roomId, draft, replyingToEvent!!)
                                        }
                                        replyingToEvent = null // Clear reply state
                                        messageSoundPlayer.play() // Play sound when sending reply
                                    }
                                    // Otherwise send regular message
                                    else {
                                        appViewModel.sendMessage(roomId, draft)
                                        messageSoundPlayer.play() // Play sound when sending message
                                    }
                                    draft = "" // Clear the input after sending
                                }
                            },
                            enabled = draft.isNotBlank() && isInputEnabled,
                            shape = CircleShape, // Perfect circle
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            modifier = Modifier.size(buttonHeight), // Fixed height matching single-line text field
                            contentPadding = PaddingValues(0.dp) // No padding for perfect circle
                        ) {
                            if (showSendIndicator) {
                                ContainedExpressiveLoadingIndicator(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                    shape = CircleShape,
                                    containerColor =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    indicatorColor =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    contentPadding = 4.dp
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = "Send",
                                    tint =
                                        if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                }
                
                // In-app camera snapping overlay (full-frame between header and message box)
                AnimatedVisibility(
                    visible = showCameraOverlay,
                    enter =
                        fadeIn(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) +
                            scaleIn(
                                initialScale = 0.88f,
                                transformOrigin = TransformOrigin(0.5f, 1f),
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ),
                    exit =
                        fadeOut(
                            animationSpec = tween(160, easing = FastOutSlowInEasing)
                        ) +
                            scaleOut(
                                targetScale = 0.88f,
                                transformOrigin = TransformOrigin(0.5f, 1f),
                                animationSpec = tween(180, easing = FastOutSlowInEasing)
                            )
                ) {
                    // Track physical device orientation and rotate only camera controls (not preview container)
                    val cameraOrientation = rememberCameraOrientation()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .zIndex(12f)
                    ) {
                        // Letterboxed CameraX preview with fixed 3:4 portrait aspect.
                        // We keep the preview itself unrotated to avoid stretching/compressing;
                        // only the overlay controls rotate with device orientation.
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = (-24).dp)
                                .fillMaxHeight()
                                .aspectRatio(3f / 4f)
                        ) {
                            key(useFrontCamera) {
                                InAppCameraPreview(
                                    modifier = Modifier.fillMaxSize(),
                                    useFrontCamera = useFrontCamera,
                                    onImageCaptureReady = { imageCapture ->
                                        cameraImageCapture = imageCapture
                                    }
                                )
                            }
                        }
                        // Top-left: close (cancel) button
                        val animatedRotation by animateFloatAsState(
                            targetValue = cameraOrientation.iconAngle,
                            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                            label = "cameraControlsRotation"
                        )

                        IconButton(
                            onClick = { showCameraOverlay = false },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 16.dp, top = 32.dp)
                                .size(44.dp)
                                .graphicsLayer {
                                    rotationZ = animatedRotation
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close camera",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Top-right: flash mode toggle (OFF → AUTO → ON → OFF ...)
                        IconButton(
                            onClick = {
                                cameraFlashMode =
                                    when (cameraFlashMode) {
                                        CameraFlashMode.OFF -> CameraFlashMode.AUTO
                                        CameraFlashMode.AUTO -> CameraFlashMode.ON
                                        CameraFlashMode.ON -> CameraFlashMode.OFF
                                    }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 16.dp, top = 32.dp)
                                .size(44.dp)
                                .graphicsLayer {
                                    rotationZ = animatedRotation
                                }
                        ) {
                            val flashIcon = when (cameraFlashMode) {
                                CameraFlashMode.OFF -> Icons.Filled.FlashOff
                                CameraFlashMode.AUTO -> Icons.Filled.FlashAuto
                                CameraFlashMode.ON -> Icons.Filled.FlashOn
                            }
                            val flashLabel = when (cameraFlashMode) {
                                CameraFlashMode.OFF -> "Flash off"
                                CameraFlashMode.AUTO -> "Flash auto"
                                CameraFlashMode.ON -> "Flash on"
                            }
                            Icon(
                                imageVector = flashIcon,
                                contentDescription = flashLabel,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Center-bottom: snap button (camera icon only), just above message box
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                // Keep above message box; adjust if footer height changes
                                .padding(bottom = 80.dp)
                                .size(72.dp)
                                .graphicsLayer {
                                    rotationZ = animatedRotation
                                },
                            tonalElevation = 4.dp
                        ) {
                            IconButton(
                                onClick = {
                                    val imageCapture = cameraImageCapture
                                    if (imageCapture == null) {
                                        Log.w("Andromuks", "CameraX ImageCapture not ready yet")
                                        return@IconButton
                                    }

                                    // Align capture orientation with device rotation so saved image matches preview
                                    // Keep CameraX's targetRotation in sync with physical orientation
                                    imageCapture.targetRotation = cameraOrientation.surfaceRotation

                                    // Map our UI flash mode to CameraX flash mode
                                    imageCapture.flashMode =
                                        when (cameraFlashMode) {
                                            CameraFlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                                            CameraFlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                                            CameraFlashMode.ON -> ImageCapture.FLASH_MODE_ON
                                        }

                                    // Create a temporary file for the captured photo
                                    val photoFile = File(
                                        context.cacheDir,
                                        "andromuks_snap_${System.currentTimeMillis()}.jpg"
                                    )

                                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                                    imageCapture.takePicture(
                                        outputOptions,
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onError(exc: ImageCaptureException) {
                                                Log.e("Andromuks", "CameraX capture failed", exc)
                                            }

                                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                                val savedUri = output.savedUri ?: photoFile.toUri()
                                                // Feed into existing media preview pipeline
                                                selectedMediaUri = savedUri
                                                selectedMediaIsVideo = false
                                                showMediaPreview = true
                                                showCameraOverlay = false
                                            }
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CameraAlt,
                                    contentDescription = "Snap photo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        // Bottom-right: camera switch button (back/selfie)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 24.dp, bottom = 90.dp)
                                .size(48.dp)
                                .graphicsLayer {
                                    rotationZ = animatedRotation
                                },
                            tonalElevation = 4.dp
                        ) {
                            IconButton(
                                onClick = { useFrontCamera = !useFrontCamera },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Cameraswitch,
                                    contentDescription = "Switch camera",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Video recording overlay (parallel to photo overlay)
                AnimatedVisibility(
                    visible = showVideoOverlay,
                    enter =
                        fadeIn(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) +
                            scaleIn(
                                initialScale = 0.88f,
                                transformOrigin = TransformOrigin(0.5f, 1f),
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ),
                    exit =
                        fadeOut(
                            animationSpec = tween(160, easing = FastOutSlowInEasing)
                        ) +
                            scaleOut(
                                targetScale = 0.88f,
                                transformOrigin = TransformOrigin(0.5f, 1f),
                                animationSpec = tween(180, easing = FastOutSlowInEasing)
                            )
                ) {
                    val videoCameraOrientation = rememberCameraOrientation()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .zIndex(12f)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = (-24).dp)
                                .fillMaxHeight()
                                .aspectRatio(3f / 4f)
                        ) {
                            key(videoUseFrontCamera) {
                                InAppVideoPreview(
                                    modifier = Modifier.fillMaxSize(),
                                    useFrontCamera = videoUseFrontCamera,
                                    onVideoCaptureReady = { vc -> videoCapture = vc }
                                )
                            }
                        }

                        val videoAnimatedRotation by animateFloatAsState(
                            targetValue = videoCameraOrientation.iconAngle,
                            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                            label = "videoControlsRotation"
                        )

                        // Update record elapsed time every second while recording
                        LaunchedEffect(activeVideoRecording, videoRecordingStartTime) {
                            if (activeVideoRecording == null || videoRecordingStartTime == null) return@LaunchedEffect
                            val start = videoRecordingStartTime!!
                            while (activeVideoRecording != null) {
                                kotlinx.coroutines.delay(1000)
                                videoRecordingElapsedSeconds = ((System.currentTimeMillis() - start) / 1000).toInt()
                            }
                        }

                        IconButton(
                            onClick = {
                                activeVideoRecording?.stop()
                                activeVideoRecording = null
                                videoRecordingStartTime = null
                                showVideoOverlay = false
                            },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 16.dp, top = 32.dp)
                                .size(44.dp)
                                .graphicsLayer { rotationZ = videoAnimatedRotation }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close video camera",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                videoFlashMode = when (videoFlashMode) {
                                    CameraFlashMode.OFF -> CameraFlashMode.AUTO
                                    CameraFlashMode.AUTO -> CameraFlashMode.ON
                                    CameraFlashMode.ON -> CameraFlashMode.OFF
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 16.dp, top = 32.dp)
                                .size(44.dp)
                                .graphicsLayer { rotationZ = videoAnimatedRotation }
                        ) {
                            val flashIcon = when (videoFlashMode) {
                                CameraFlashMode.OFF -> Icons.Filled.FlashOff
                                CameraFlashMode.AUTO -> Icons.Filled.FlashAuto
                                CameraFlashMode.ON -> Icons.Filled.FlashOn
                            }
                            Icon(
                                imageVector = flashIcon,
                                contentDescription = when (videoFlashMode) {
                                    CameraFlashMode.OFF -> "Flash off"
                                    CameraFlashMode.AUTO -> "Flash auto"
                                    CameraFlashMode.ON -> "Flash on"
                                },
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Static record length (bottom-left) + Record button (bottom-center)
                        if (activeVideoRecording != null) {
                            Text(
                                text = "${videoRecordingElapsedSeconds / 60}:${(videoRecordingElapsedSeconds % 60).toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 24.dp, bottom = 80.dp)
                                    .graphicsLayer { rotationZ = videoAnimatedRotation }
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = if (activeVideoRecording != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 80.dp)
                                .size(72.dp)
                                .graphicsLayer { rotationZ = videoAnimatedRotation },
                            tonalElevation = 4.dp
                        ) {
                            IconButton(
                                onClick = {
                                    val vc = videoCapture
                                    if (vc == null) {
                                        Log.w("Andromuks", "VideoCapture not ready yet")
                                        return@IconButton
                                    }
                                    if (activeVideoRecording != null) {
                                        activeVideoRecording?.stop()
                                        activeVideoRecording = null
                                        videoRecordingStartTime = null
                                        videoRecordingElapsedSeconds = 0
                                        return@IconButton
                                    }
                                    vc.targetRotation = videoCameraOrientation.surfaceRotation
                                    val videoFile = File(
                                        context.cacheDir,
                                        "andromuks_video_${System.currentTimeMillis()}.mp4"
                                    )
                                    val fileOutputOptions = FileOutputOptions.Builder(videoFile).build()
                                    val executor = ContextCompat.getMainExecutor(context)
                                    val pendingRecording = vc.output.prepareRecording(context, fileOutputOptions)
                                    val rec = if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        pendingRecording.withAudioEnabled()
                                    } else {
                                        pendingRecording
                                    }.start(executor) { event ->
                                        when (event) {
                                            is VideoRecordEvent.Finalize -> {
                                                videoRecordingStartTime = null
                                                videoRecordingElapsedSeconds = 0
                                                // FileOutputOptions: use the file we created
                                                selectedMediaUri = videoFile.toUri()
                                                selectedMediaIsVideo = true
                                                showMediaPreview = true
                                                showVideoOverlay = false
                                            }
                                            else -> {}
                                        }
                                    }
                                    activeVideoRecording = rec
                                    videoRecordingStartTime = System.currentTimeMillis()
                                    videoRecordingElapsedSeconds = 0
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Videocam,
                                    contentDescription = if (activeVideoRecording != null) "Stop recording" else "Start recording",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            //}
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 24.dp, bottom = 90.dp)
                                .size(48.dp)
                                .graphicsLayer { rotationZ = videoAnimatedRotation },
                            tonalElevation = 4.dp
                        ) {
                            IconButton(
                                onClick = { videoUseFrontCamera = !videoUseFrontCamera },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Cameraswitch,
                                    contentDescription = "Switch camera",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                
                // Floating action button to scroll to bottom (only shown when detached)
                // Keep this in the Box so it can overlay the content
                if (!isAttachedToBottom) {
                    // Push FAB up when attach menu or message menu is open
                    val menuOpen = showAttachmentMenu || messageMenuConfig != null
                    val fabBottomPadding = if (menuOpen) 200.dp else 90.dp // Higher when menu is open to avoid clipping
                    FloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                // Animated scroll to bottom, then re-attach (FAB hides once settled)
                                // With reverseLayout, index 0 is bottom
                                animatedScrollTo(0)
                                isAttachedToBottom = true
                                if (BuildConfig.DEBUG) Log.d(
                                    "Andromuks",
                                    "RoomTimelineScreen: FAB clicked, animateScrollToItem to bottom and re-attaching - reverseLayout"
                                )
                            }
                        },
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(
                                    end = 16.dp,
                                    bottom = fabBottomPadding
                                )
                                .navigationBarsPadding()
                                .imePadding(), // Above text input and keyboard
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Scroll to bottom"
                        )
                    }
                }
                
                // Message menu bar (slides from bottom, same position as attach menu)
                AnimatedVisibility(
                    visible = messageMenuConfig != null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                    enter = fadeIn(initialAlpha = 1f, animationSpec = tween(durationMillis = 120)),
                    exit = fadeOut(targetAlpha = 1f, animationSpec = tween(durationMillis = 120))
                ) {
                    val messageBarSlideOffsetPx = transition.animateFloat(
                        transitionSpec = {
                            if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                                // ENTER: slide in first
                                tween(durationMillis = 120)
                            } else {
                                // EXIT: wait for buttons to fade out, then slide down
                                tween(durationMillis = 120, delayMillis = 500)
                            }
                        },
                        label = "messageBarSlideOffset"
                    ) { state ->
                        if (state == EnterExitState.Visible) 0f else with(density) { 56.dp.toPx() }
                    }
                    val messageButtonsAlpha = transition.animateFloat(
                        transitionSpec = {
                            if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                                // ENTER: buttons fade in after bar has slid in
                                tween(durationMillis = 500, delayMillis = 120)
                            } else {
                                // EXIT: buttons fade out immediately
                                tween(durationMillis = 500)
                            }
                        },
                        label = "messageButtonsAlpha"
                    ) { state ->
                        if (state == EnterExitState.Visible) 1f else 0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // Position menu right above footer (same as attach menu)
                                // Footer height = buttonHeight + 24.dp padding
                                translationY = -with(density) { (buttonHeight + 24.dp).toPx() } + messageBarSlideOffsetPx.value
                            }
                            .navigationBarsPadding()
                            .imePadding()
                            .zIndex(5f) // Ensure it's above other content
                    ) {
                        net.vrkknn.andromuks.utils.MessageMenuBar(
                            menuConfig = messageMenuConfig ?: retainedMessageMenuConfig,
                            onDismiss = { messageMenuConfig = null },
                            buttonsAlpha = messageButtonsAlpha.value,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                // Attachment menu overlay - horizontal floating action bar above footer
                AnimatedVisibility(
                    visible = showAttachmentMenu,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                    enter = fadeIn(initialAlpha = 1f, animationSpec = tween(durationMillis = 120)),
                    exit = fadeOut(targetAlpha = 1f, animationSpec = tween(durationMillis = 120))
                ) {
                    val attachmentBarSlideOffsetPx = transition.animateFloat(
                        transitionSpec = {
                            if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                                // ENTER: slide in first
                                tween(durationMillis = 120)
                            } else {
                                // EXIT: wait for buttons to fade out, then slide down
                                tween(durationMillis = 120, delayMillis = 500)
                            }
                        },
                        label = "attachmentBarSlideOffset"
                    ) { state ->
                        if (state == EnterExitState.Visible) 0f else with(density) { 56.dp.toPx() }
                    }
                    val attachmentButtonsAlpha = transition.animateFloat(
                        transitionSpec = {
                            if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                                // ENTER: buttons fade in after bar has slid in
                                tween(durationMillis = 500, delayMillis = 120)
                            } else {
                                // EXIT: buttons fade out immediately
                                tween(durationMillis = 500)
                            }
                        },
                        label = "attachmentButtonsAlpha"
                    ) { state ->
                        if (state == EnterExitState.Visible) 1f else 0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // Position menu right above footer (footer height = buttonHeight + 24.dp padding)
                                translationY = -with(density) { (buttonHeight + 24.dp).toPx() } + attachmentBarSlideOffsetPx.value
                            }
                            .navigationBarsPadding()
                            .imePadding()
                            .zIndex(5f) // Ensure it's above other content
                    ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Files option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            launchPickerWithPermission("file", "*/*")
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Folder,
                                            contentDescription = "Files",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.alpha(attachmentButtonsAlpha.value)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "File",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Audio option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            launchPickerWithPermission("audio", "audio/*")
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.AudioFile,
                                            contentDescription = "Audio",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.alpha(attachmentButtonsAlpha.value)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Audio",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Image/Video option
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            launchPickerWithPermission("image", "*/*")
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Image,
                                            contentDescription = "Images & Videos",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.alpha(attachmentButtonsAlpha.value)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Image/Video",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            
                            // Photo option (opens in-app snapping overlay) with expressive shape morph
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                val photoInteractionSource = remember { MutableInteractionSource() }
                                val photoPressed by photoInteractionSource.collectIsPressedAsState()

                                val photoShapePercent by animateFloatAsState(
                                    targetValue = if (photoPressed) 35f else 50f, // 50 = circle, 35 ~ squircle
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "photoShapeMorph"
                                )

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(percent = photoShapePercent.toInt()),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            // Close attach menu and open in-app camera overlay
                                            showAttachmentMenu = false
                                            showCameraOverlay = true
                                        },
                                        interactionSource = photoInteractionSource,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CameraAlt,
                                            contentDescription = "Photo",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.alpha(attachmentButtonsAlpha.value)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Photo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Video option (opens in-app video recording overlay)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier
                                        .size(56.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            showAttachmentMenu = false
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                showVideoOverlay = true
                                            } else {
                                                cameraVideoPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Videocam,
                                            contentDescription = "Video",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.alpha(attachmentButtonsAlpha.value)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Video",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    }
                }
                
                // Floating emoji shortcode suggestion list
                if (showEmojiSuggestionList) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 72.dp, // Align with text input (attach button width + spacing)
                                bottom = 60.dp  // Closer to text input
                            )
                            .navigationBarsPadding()
                            .imePadding()
                            .zIndex(9f)
                    ) {
                        EmojiSuggestionList(
                            query = emojiQuery,
                            customEmojiPacks = appViewModel.customEmojiPacks,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            onSuggestionSelected = { suggestion ->
                                val currentText = draft
                                val cursorPos = textFieldValue.selection.start
                                val endIndex = cursorPos
                                
                                val baseReplacement =
                                    suggestion.emoji
                                        ?: suggestion.customEmoji?.let { custom ->
                                            "![:${custom.name}:](${custom.mxcUrl} \"Emoji: :${custom.name}:\")"
                                        }
                                        ?: ""
                                
                                if (baseReplacement.isNotEmpty() && emojiStartIndex >= 0 && emojiStartIndex < endIndex) {
                                    val newText =
                                        currentText.substring(0, emojiStartIndex) +
                                            baseReplacement +
                                            currentText.substring(endIndex)
                                    val newCursor = emojiStartIndex + baseReplacement.length
                                    
                                    draft = newText
                                    textFieldValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursor)
                                    )
                                    
                                    // Update recent emojis (reuse logic from EmojiSelectionDialog for custom emojis)
                                    val emojiForRecent =
                                        if (baseReplacement.startsWith("![:") && baseReplacement.contains("mxc://")) {
                                            val mxcStart = baseReplacement.indexOf("mxc://")
                                            if (mxcStart >= 0) {
                                                val mxcEnd = baseReplacement.indexOf("\"", mxcStart)
                                                if (mxcEnd > mxcStart) {
                                                    baseReplacement.substring(mxcStart, mxcEnd)
                                                } else {
                                                    baseReplacement.substring(mxcStart)
                                                }
                                            } else {
                                                baseReplacement
                                            }
                                        } else {
                                            baseReplacement
                                        }
                                    appViewModel.updateRecentEmojis(emojiForRecent)
                                }
                                
                                showEmojiSuggestionList = false
                                emojiQuery = ""
                            },
                            modifier = Modifier.zIndex(10f)
                        )
                    }
                }
                
                // Floating command suggestion list
                if (showCommandSuggestionList) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 72.dp, // Align with text input (attach button width + spacing)
                                bottom = 60.dp  // Closer to text input
                            )
                            .navigationBarsPadding()
                            .imePadding()
                            .zIndex(9f)
                    ) {
                        CommandSuggestionList(
                            query = commandQuery,
                            onCommandSelected = { command ->
                                // Replace the command text with the selected command
                                val commandEndIndex = commandStartIndex + 1 + commandQuery.length
                                val newText = draft.substring(0, commandStartIndex) + command.command + " " + draft.substring(commandEndIndex)
                                val newCursorPosition = commandStartIndex + command.command.length + 1
                                
                                draft = newText
                                textFieldValue = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(newCursorPosition)
                                )
                                
                                // Hide the command suggestion list
                                showCommandSuggestionList = false
                                commandQuery = ""
                            },
                            modifier = Modifier.zIndex(10f)
                        )
                    }
                }
                
                // Floating room suggestion list for room mentions
                if (showRoomSuggestionList) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 72.dp, // Align with text input (attach button width + spacing)
                                bottom = 60.dp  // Closer to text input
                            )
                            .navigationBarsPadding()
                            .imePadding()
                            .zIndex(9f)
                    ) {
                        RoomSuggestionList(
                            rooms = roomsWithAliases,
                            query = roomQuery,
                            onRoomSelect = { selectedRoomId, canonicalAlias ->
                                // Replace the room mention text with a markdown link
                                // Format: [#room:server.com](https://matrix.to/#/%23room%3Aserver.com)
                                val roomEndIndex = roomStartIndex + 1 + roomQuery.length
                                val encodedAlias = java.net.URLEncoder.encode(canonicalAlias, "UTF-8")
                                val roomMentionText = "[$canonicalAlias](https://matrix.to/#/$encodedAlias) "
                                val newText = draft.substring(0, roomStartIndex) + roomMentionText + draft.substring(roomEndIndex)
                                val newCursorPosition = roomStartIndex + roomMentionText.length
                                
                                draft = newText
                                textFieldValue = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(newCursorPosition)
                                )
                                
                                // Hide the room suggestion list
                                showRoomSuggestionList = false
                                roomQuery = ""
                            },
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = authToken,
                            modifier = Modifier.zIndex(10f)
                        )
                    }
                }
                
                // Floating member list for mentions
                if (showMentionList) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 72.dp, // Align with text input (attach button width + spacing)
                                bottom = 60.dp  // Closer to text input
                            )
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                        MentionMemberList(
                            members = roomMembers,
                            query = mentionQuery,
                            onMemberSelect = { userId, displayName ->
                                // Replace the mention text with the selected user
                                val mentionEndIndex = mentionStartIndex + 1 + mentionQuery.length
                                val newText = handleMentionSelection(userId, displayName, draft, mentionStartIndex, mentionEndIndex)
                                
                                // Calculate the new cursor position after the inserted mention
                                // The cursor should be positioned right after the inserted mention text
                                val escapedDisplayName = (displayName ?: userId.removePrefix("@"))
                                    .replace("[", "\\[")
                                    .replace("]", "\\]")
                                val mentionText = "[$escapedDisplayName](https://matrix.to/#/$userId)"
                                val newCursorPosition = mentionStartIndex + mentionText.length
                                
                                draft = newText
                                textFieldValue = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(newCursorPosition)
                                )
                                
                                // Hide the mention list
                                showMentionList = false
                                mentionQuery = ""
                            },
                            homeserverUrl = appViewModel.homeserverUrl,
                            authToken = authToken,
                            modifier = Modifier.zIndex(10f)
                        )
                    }
                }
                
                // Delete confirmation dialog (with optional reason)
                if (showDeleteDialog && deletingEvent != null) {
                    DeleteMessageDialog(
                        onDismiss = {
                            showDeleteDialog = false
                            deletingEvent = null
                        },
                        onConfirm = { reason ->
                            // Send delete request with optional reason
                            appViewModel.sendDelete(roomId, deletingEvent!!, reason)
                            showDeleteDialog = false
                            deletingEvent = null
                        }
                    )
                }
                
                // Room joiner screen
                if (showRoomJoiner && roomLinkToJoin != null) {
                    RoomJoinerScreen(
                        roomLink = roomLinkToJoin!!,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        appViewModel = appViewModel,
                        onDismiss = {
                            showRoomJoiner = false
                            roomLinkToJoin = null
                        },
                        onJoinSuccess = { joinedRoomId ->
                            showRoomJoiner = false
                            roomLinkToJoin = null
                            // Navigate to the joined room
                            appViewModel.joinRoomAndNavigate(joinedRoomId, navController)
                        }
                    )
                }
                
                // Emoji selection dialog for reactions
                if (showEmojiSelection && reactingToEvent != null) {
                    EmojiSelectionDialog(
                        recentEmojis = appViewModel.recentEmojis,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onEmojiSelected = { emoji ->
                            // Send reaction
                            appViewModel.sendReaction(roomId, reactingToEvent!!.eventId, emoji)
                            showEmojiSelection = false
                            reactingToEvent = null
                        },
                        onDismiss = {
                            showEmojiSelection = false
                            reactingToEvent = null
                        },
                        customEmojiPacks = appViewModel.customEmojiPacks
                    )
                }
                
                // Emoji selection dialog for text input
                if (showEmojiPickerForText) {
                    EmojiSelectionDialog(
                        recentEmojis = appViewModel.recentEmojis,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onEmojiSelected = { emoji ->
                            // Insert emoji at cursor position
                            val currentText = textFieldValue.text
                            val cursorPosition = textFieldValue.selection.start
                            val newText = currentText.substring(0, cursorPosition) + 
                                         emoji + 
                                         currentText.substring(cursorPosition)
                            val newCursorPosition = cursorPosition + emoji.length
                            
                            // Update both draft and textFieldValue
                            draft = newText
                            textFieldValue = TextFieldValue(
                                text = newText,
                                selection = TextRange(newCursorPosition)
                            )
                            
                            // Update recent emojis (updates in-memory state and sends to backend)
                            // This will persist via account_data and update the recent emoji tab
                            // For custom emojis, extract MXC URL from formatted string
                            val emojiForRecent = if (emoji.startsWith("![:") && emoji.contains("mxc://")) {
                                // Extract MXC URL from format: ![:name:](mxc://url "Emoji: :name:")
                                val mxcStart = emoji.indexOf("mxc://")
                                if (mxcStart >= 0) {
                                    val mxcEnd = emoji.indexOf("\"", mxcStart)
                                    if (mxcEnd > mxcStart) {
                                        emoji.substring(mxcStart, mxcEnd)
                                    } else {
                                        emoji.substring(mxcStart)
                                    }
                                } else {
                                    emoji
                                }
                            } else {
                                emoji
                            }
                            appViewModel.updateRecentEmojis(emojiForRecent)
                            
                            // Don't close the picker - user might want to add more emojis
                        },
                        onDismiss = {
                            showEmojiPickerForText = false
                        },
                        customEmojiPacks = appViewModel.customEmojiPacks,
                        allowCustomReactions = false
                    )
                }
                
                // Sticker selection dialog for text input
                if (showStickerPickerForText) {
                    StickerSelectionDialog(
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onStickerSelected = { sticker ->
                            // Send sticker message
                            val mimeType = sticker.info?.optString("mimetype") ?: "image/png"
                            val size = sticker.info?.optLong("size") ?: 0L
                            val width = sticker.info?.optInt("w", 0) ?: 0
                            val height = sticker.info?.optInt("h", 0) ?: 0
                            val body = sticker.body ?: sticker.name
                            
                            appViewModel.sendStickerMessage(
                                roomId = roomId,
                                mxcUrl = sticker.mxcUrl,
                                body = body,
                                mimeType = mimeType,
                                size = size,
                                width = width,
                                height = height
                            )
                            
                            showStickerPickerForText = false
                        },
                        onDismiss = {
                            showStickerPickerForText = false
                        },
                        stickerPacks = appViewModel.stickerPacks
                    )
                }
                
                // Multiple media preview dialog (from share with 2+ items): swipe through, caption each, send all
                val multiItems = selectedMediaItems
                if (multiItems != null && multiItems.isNotEmpty()) {
                    MediaPreviewDialogMultiple(
                        items = multiItems,
                        onDismiss = { selectedMediaItems = null },
                        onSendAll = { list ->
                            selectedMediaItems = null
                            coroutineScope.launch {
                                // Local helper for uploads with retry
                                suspend fun <T> performUpload(
                                    type: String,
                                    uploadBlock: suspend () -> T?
                                ): T? {
                                    appViewModel.beginUpload(roomId, type)
                                    try {
                                        var result: T? = null
                                        for (attempt in 0..3) {
                                            if (attempt > 0) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Retrying $type upload (attempt $attempt/3)")
                                                appViewModel.setUploadRetryCount(roomId, attempt)
                                                kotlinx.coroutines.delay(1000L * attempt)
                                            }
                                            result = uploadBlock()
                                            if (result != null) break
                                        }
                                        appViewModel.setUploadRetryCount(roomId, 0)
                                        return result
                                    } finally {
                                        appViewModel.endUpload(roomId, type)
                                    }
                                }

                                for ((index, sendState) in list.withIndex()) {
                                    val uri = sendState.item.uri
                                    val mime = sendState.item.mimeType ?: context.contentResolver.getType(uri) ?: ""
                                    val caption = sendState.caption.takeIf { it.isNotBlank() }
                                    try {
                                        when {
                                            mime.startsWith("video/") -> {
                                                val videoResult = performUpload("video") {
                                                    VideoUploadUtils.uploadVideo(
                                                        context = context,
                                                        uri = uri,
                                                        homeserverUrl = homeserverUrl,
                                                        authToken = authToken,
                                                        isEncrypted = false,
                                                        onProgress = { key, p ->
                                                            appViewModel.setUploadProgress(roomId, key, p)
                                                        }
                                                    )
                                                }
                                                if (videoResult != null) {
                                                    appViewModel.sendVideoMessage(
                                                        roomId = roomId,
                                                        videoMxcUrl = videoResult.videoMxcUrl,
                                                        thumbnailMxcUrl = videoResult.thumbnailMxcUrl,
                                                        width = videoResult.width,
                                                        height = videoResult.height,
                                                        duration = videoResult.duration,
                                                        size = videoResult.size,
                                                        mimeType = videoResult.mimeType,
                                                        thumbnailBlurHash = videoResult.thumbnailBlurHash,
                                                        thumbnailWidth = videoResult.thumbnailWidth,
                                                        thumbnailHeight = videoResult.thumbnailHeight,
                                                        thumbnailSize = videoResult.thumbnailSize,
                                                        caption = caption
                                                    )
                                                }
                                            }
                                            mime.startsWith("audio/") -> {
                                                val audioResult = performUpload("audio") {
                                                    MediaUploadUtils.uploadAudio(
                                                        context = context,
                                                        uri = uri,
                                                        homeserverUrl = homeserverUrl,
                                                        authToken = authToken,
                                                        isEncrypted = false,
                                                        onProgress = { key, p ->
                                                            appViewModel.setUploadProgress(roomId, key, p)
                                                        }
                                                    )
                                                }
                                                if (audioResult != null) {
                                                    appViewModel.sendAudioMessage(
                                                        roomId = roomId,
                                                        mxcUrl = audioResult.mxcUrl,
                                                        filename = audioResult.filename,
                                                        duration = audioResult.duration,
                                                        size = audioResult.size,
                                                        mimeType = audioResult.mimeType,
                                                        caption = caption
                                                    )
                                                }
                                            }
                                            mime.startsWith("image/") -> {
                                                val uploadResult = performUpload("image") {
                                                    MediaUploadUtils.uploadMedia(
                                                        context = context,
                                                        uri = uri,
                                                        homeserverUrl = homeserverUrl,
                                                        authToken = authToken,
                                                        isEncrypted = false,
                                                        compressOriginal = sendState.compressOriginal,
                                                        onProgress = { key, p ->
                                                            appViewModel.setUploadProgress(roomId, key, p)
                                                        }
                                                    )
                                                }
                                                if (uploadResult != null) {
                                                    appViewModel.sendImageMessage(
                                                        roomId = roomId,
                                                        mxcUrl = uploadResult.mxcUrl,
                                                        width = uploadResult.width,
                                                        height = uploadResult.height,
                                                        size = uploadResult.size,
                                                        mimeType = uploadResult.mimeType,
                                                        blurHash = uploadResult.blurHash,
                                                        caption = caption,
                                                        thumbnailUrl = uploadResult.thumbnailUrl,
                                                        thumbnailWidth = uploadResult.thumbnailWidth,
                                                        thumbnailHeight = uploadResult.thumbnailHeight,
                                                        thumbnailMimeType = uploadResult.thumbnailMimeType,
                                                        thumbnailSize = uploadResult.thumbnailSize
                                                    )
                                                }
                                            }
                                            else -> {
                                                val fileResult = performUpload("file") {
                                                    MediaUploadUtils.uploadFile(
                                                        context = context,
                                                        uri = uri,
                                                        homeserverUrl = homeserverUrl,
                                                        authToken = authToken,
                                                        isEncrypted = false,
                                                        onProgress = { key, p ->
                                                            appViewModel.setUploadProgress(roomId, key, p)
                                                        }
                                                    )
                                                }
                                                if (fileResult != null) {
                                                    appViewModel.sendFileMessage(
                                                        roomId = roomId,
                                                        mxcUrl = fileResult.mxcUrl,
                                                        filename = fileResult.filename,
                                                        size = fileResult.size,
                                                        mimeType = fileResult.mimeType,
                                                        caption = caption
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Andromuks", "RoomTimelineScreen: Multi-send item ${index + 1} failed", e)
                                        android.widget.Toast.makeText(
                                            context,
                                            "Failed to send item ${index + 1}: ${e.message}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                }
                
                // Media preview dialog (shows selected media with caption input)
                if (showMediaPreview && (selectedMediaUri != null || selectedAudioUri != null || selectedFileUri != null)) {
                    val currentUri = selectedMediaUri ?: selectedAudioUri ?: selectedFileUri!!
                    val isAudio = selectedAudioUri != null
                    val isFile = selectedFileUri != null
                    
                    MediaPreviewDialog(
                        uri = currentUri,
                        isVideo = selectedMediaIsVideo,
                        isAudio = isAudio,
                        isFile = isFile,
                        onDismiss = {
                            showMediaPreview = false
                            selectedMediaUri = null
                            selectedAudioUri = null
                            selectedFileUri = null
                            selectedMediaIsVideo = false
                        },
                        onSend = { caption, compressOriginal ->
                            // Close dialog immediately - upload will continue in background
                            showMediaPreview = false
                            
                            // Clear media selection state immediately so user can select new media
                            val mediaUriToUpload = selectedMediaUri
                            val audioUriToUpload = selectedAudioUri
                            val fileUriToUpload = selectedFileUri
                            val isVideoToUpload = selectedMediaIsVideo
                            
                            // Clear state immediately
                            selectedMediaUri = null
                            selectedAudioUri = null
                            selectedFileUri = null
                            selectedMediaIsVideo = false
                            
                            // Upload and send in background
                            coroutineScope.launch {
                                // Local helper for uploads with retry
                                suspend fun <T> performUpload(
                                    type: String,
                                    uploadBlock: suspend () -> T?
                                ): T? {
                                    appViewModel.beginUpload(roomId, type)
                                    try {
                                        var result: T? = null
                                        for (attempt in 0..3) {
                                            if (attempt > 0) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Retrying $type upload (attempt $attempt/3)")
                                                appViewModel.setUploadRetryCount(roomId, attempt)
                                                kotlinx.coroutines.delay(1000L * attempt)
                                            }
                                            result = uploadBlock()
                                            if (result != null) break
                                        }
                                        appViewModel.setUploadRetryCount(roomId, 0)
                                        return result
                                    } finally {
                                        appViewModel.endUpload(roomId, type)
                                    }
                                }

                                try {
                                    when {
                                        isVideoToUpload && mediaUriToUpload != null -> {
                                            // Upload video with thumbnail
                                            val videoResult = performUpload("video") {
                                                VideoUploadUtils.uploadVideo(
                                                    context = context,
                                                    uri = mediaUriToUpload,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    isEncrypted = false,
                                                    onProgress = { key, p ->
                                                        appViewModel.setUploadProgress(roomId, key, p)
                                                    }
                                                )
                                            }
                                            
                                            if (videoResult != null) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Video upload successful, sending message")
                                                // Send video message with metadata
                                                appViewModel.sendVideoMessage(
                                                    roomId = roomId,
                                                    videoMxcUrl = videoResult.videoMxcUrl,
                                                    thumbnailMxcUrl = videoResult.thumbnailMxcUrl,
                                                    width = videoResult.width,
                                                    height = videoResult.height,
                                                    duration = videoResult.duration,
                                                    size = videoResult.size,
                                                    mimeType = videoResult.mimeType,
                                                    thumbnailBlurHash = videoResult.thumbnailBlurHash,
                                                    thumbnailWidth = videoResult.thumbnailWidth,
                                                    thumbnailHeight = videoResult.thumbnailHeight,
                                                    thumbnailSize = videoResult.thumbnailSize,
                                                    caption = caption.takeIf { it.isNotBlank() }
                                                )
                                            } else {
                                                Log.e("Andromuks", "RoomTimelineScreen: Video upload failed after retries")
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Failed to upload video after 3 attempts",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        audioUriToUpload != null -> {
                                            // Upload audio
                                            val audioResult = performUpload("audio") {
                                                MediaUploadUtils.uploadAudio(
                                                    context = context,
                                                    uri = audioUriToUpload,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    isEncrypted = false,
                                                    onProgress = { key, p ->
                                                        appViewModel.setUploadProgress(roomId, key, p)
                                                    }
                                                )
                                            }
                                            
                                            if (audioResult != null) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Audio upload successful, sending message")
                                                // Send audio message with metadata
                                                appViewModel.sendAudioMessage(
                                                    roomId = roomId,
                                                    mxcUrl = audioResult.mxcUrl,
                                                    filename = audioResult.filename,
                                                    duration = audioResult.duration,
                                                    size = audioResult.size,
                                                    mimeType = audioResult.mimeType,
                                                    caption = caption.takeIf { it.isNotBlank() }
                                                )
                                            } else {
                                                Log.e("Andromuks", "RoomTimelineScreen: Audio upload failed after retries")
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Failed to upload audio after 3 attempts",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        fileUriToUpload != null -> {
                                            // Upload file
                                            val fileResult = performUpload("file") {
                                                MediaUploadUtils.uploadFile(
                                                    context = context,
                                                    uri = fileUriToUpload,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    isEncrypted = false,
                                                    onProgress = { key, p ->
                                                        appViewModel.setUploadProgress(roomId, key, p)
                                                    }
                                                )
                                            }
                                            
                                            if (fileResult != null) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: File upload successful, sending message")
                                                // Send file message with metadata
                                                appViewModel.sendFileMessage(
                                                    roomId = roomId,
                                                    mxcUrl = fileResult.mxcUrl,
                                                    filename = fileResult.filename,
                                                    size = fileResult.size,
                                                    mimeType = fileResult.mimeType,
                                                    caption = caption.takeIf { it.isNotBlank() }
                                                )
                                            } else {
                                                Log.e("Andromuks", "RoomTimelineScreen: File upload failed after retries")
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Failed to upload file after 3 attempts",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        mediaUriToUpload != null -> {
                                            // Upload image
                                            val uploadResult = performUpload("image") {
                                                MediaUploadUtils.uploadMedia(
                                                    context = context,
                                                    uri = mediaUriToUpload,
                                                    homeserverUrl = homeserverUrl,
                                                    authToken = authToken,
                                                    isEncrypted = false,
                                                    compressOriginal = compressOriginal,
                                                    onProgress = { key, p ->
                                                        appViewModel.setUploadProgress(roomId, key, p)
                                                    }
                                                )
                                            }
                                            
                                            if (uploadResult != null) {
                                                if (BuildConfig.DEBUG) Log.d("Andromuks", "RoomTimelineScreen: Image upload successful, sending message")
                                                // Send image message with metadata
                                                appViewModel.sendImageMessage(
                                                    roomId = roomId,
                                                    mxcUrl = uploadResult.mxcUrl,
                                                    width = uploadResult.width,
                                                    height = uploadResult.height,
                                                    size = uploadResult.size,
                                                    mimeType = uploadResult.mimeType,
                                                    blurHash = uploadResult.blurHash,
                                                    caption = caption.takeIf { it.isNotBlank() },
                                                    thumbnailUrl = uploadResult.thumbnailUrl,
                                                    thumbnailWidth = uploadResult.thumbnailWidth,
                                                    thumbnailHeight = uploadResult.thumbnailHeight,
                                                    thumbnailMimeType = uploadResult.thumbnailMimeType,
                                                    thumbnailSize = uploadResult.thumbnailSize
                                                )
                                            } else {
                                                Log.e("Andromuks", "RoomTimelineScreen: Image upload failed after retries")
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Failed to upload image after 3 attempts",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("Andromuks", "RoomTimelineScreen: Upload error", e)
                                    // Try to clean up upload state - determine type from what was being uploaded
                                    when {
                                        isVideoToUpload && mediaUriToUpload != null -> appViewModel.endUpload(roomId, "video")
                                        audioUriToUpload != null -> appViewModel.endUpload(roomId, "audio")
                                        fileUriToUpload != null -> appViewModel.endUpload(roomId, "file")
                                        mediaUriToUpload != null -> appViewModel.endUpload(roomId, "image")
                                        else -> appViewModel.endUpload(roomId, "image")
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        "Error uploading media: ${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
                
                // Uploading dialog removed - uploads now happen in background with status row indicator
                
                // Code viewer dialog
                if (showCodeViewer) {
                    CodeViewer(
                        code = codeViewerContent,
                        onDismiss = {
                            showCodeViewer = false
                            codeViewerContent = ""
                        }
                    )
                }

                if (showReactionsDialog && reactionsEventId != null) {
                    val reactions = reactionsEventId?.let { appViewModel.messageReactions[it] } ?: emptyList()
                    net.vrkknn.andromuks.utils.ReactionDetailsDialog(
                        reactions = reactions,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onDismiss = { showReactionsDialog = false },
                        appViewModel = appViewModel,
                        roomId = roomId
                    )
                }

                if (showBridgeDeliveryDialog && bridgeDeliveryEventId != null) {
                    val eventId = bridgeDeliveryEventId!!
                    val deliveryInfo = appViewModel.messageBridgeDeliveryInfo[eventId] ?: net.vrkknn.andromuks.BridgeDeliveryInfo()
                    val deliveryStatus = appViewModel.messageBridgeSendStatus[eventId] ?: "sent"
                    val networkName = appViewModel.currentRoomState?.bridgeInfo?.displayName
                    net.vrkknn.andromuks.utils.BridgeDeliveryInfoDialog(
                        deliveryInfo = deliveryInfo,
                        status = deliveryStatus,
                        networkName = networkName,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        onDismiss = { showBridgeDeliveryDialog = false },
                        appViewModel = appViewModel,
                        roomId = roomId
                    )
                }
                }
            }
        }
    }
}







@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RoomHeader(
    roomState: RoomState?,
    fallbackName: String,
    fallbackAvatarUrl: String? = null,
    homeserverUrl: String,
    authToken: String,
    roomId: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    onHeaderClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onCallClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {}
) {
    // Debug logging
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: roomState = $roomState")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: fallbackName = $fallbackName")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: homeserverUrl = $homeserverUrl")
    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: authToken = ${authToken.take(10)}...")
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // Room avatar (clickable for room info)
            Box(
                modifier = Modifier.clickable(onClick = onHeaderClick)
            ) {
                if (sharedTransitionScope != null && animatedVisibilityScope != null && roomId != null) {
                    val sharedKey = "avatar-${roomId}"
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: opening room $roomId with sharedKey = $sharedKey")
                    with(sharedTransitionScope) {
                        AvatarImage(
                            mxcUrl = roomState?.avatarUrl ?: fallbackAvatarUrl,
                            homeserverUrl = homeserverUrl,
                            authToken = authToken,
                            fallbackText = roomState?.name ?: fallbackName,
                            size = 48.dp,
                            userId = roomId,
                            displayName = roomState?.name ?: fallbackName,
                            isVisible = true,
                            useCircleCache = true, // CRITICAL: Match RoomListScreen's cache path for smooth shared element transitions
                            modifier = Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = sharedKey),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    },
                                    renderInOverlayDuringTransition = true,
                                    zIndexInOverlay = 1f
                                )
                                .clip(CircleShape)
                        )
                    }
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("Andromuks", "RoomHeader: failed, we loaded the ELSE block")
                    AvatarImage(
                        mxcUrl = roomState?.avatarUrl ?: fallbackAvatarUrl,
                        homeserverUrl = homeserverUrl,
                        authToken = authToken,
                        fallbackText = roomState?.name ?: fallbackName,
                        size = 48.dp,
                        userId = roomId ?: roomState?.roomId,
                        displayName = roomState?.name ?: fallbackName,
                        isVisible = true,
                        useCircleCache = true, // CRITICAL: Match RoomListScreen's cache path for consistent avatar loading
                        modifier = Modifier.clip(CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Room info (clickable for room info)
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onHeaderClick)
            ) {
                // Room name (prefer room state name, fallback to fallback name)
                val displayName = roomState?.name ?: fallbackName

                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )

                // Room topic (below display name)
                val roomTopic = roomState?.topic
                if (roomTopic != null && roomTopic.isNotBlank()) {
                    Text(
                        text = roomTopic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            
            val bridgeInfo = roomState?.bridgeInfo
            IconButton(onClick = onCallClick) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.Videocam,
                    contentDescription = "Start call",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (bridgeInfo != null && bridgeInfo.hasRenderableIcon) {
                BridgeNetworkBadge(
                    bridgeInfo = bridgeInfo,
                    homeserverUrl = homeserverUrl,
                    authToken = authToken,
                    onClick = onRefreshClick
                )
            } else {
                IconButton(onClick = onRefreshClick) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Refresh,
                        contentDescription = "Refresh timeline",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun Context.findActivityForFinish(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
