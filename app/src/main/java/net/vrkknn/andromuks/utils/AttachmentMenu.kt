package net.vrkknn.andromuks.utils

import net.vrkknn.andromuks.ui.theme.scaledStiffness
import net.vrkknn.andromuks.ui.theme.scaledTweenMs
import android.Manifest
import android.net.Uri
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import java.io.File

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Shared attachment menu + in-app camera/video capture overlays.
 *
 * Both [net.vrkknn.andromuks.RoomTimelineScreen] and
 * [net.vrkknn.andromuks.ThreadViewerScreen] render the same composer footer, so the
 * attach bar (the labeled-chip row that slides above the footer) and the two CameraX
 * capture overlays live here to avoid the two screens drifting apart.
 *
 * Each composable owns its own [AnimatedVisibility] and internal capture state; callers
 * supply only the visibility flag plus action callbacks. Image/Audio/File go through the
 * caller's pickers; Photo/Video open the in-app overlays once CAMERA permission is granted.
 */

// Simple flash mode enum for camera overlay
enum class CameraFlashMode { OFF, AUTO, ON }

/**
 * The attachment action bar: a full-width row of labeled chip buttons that slides up above
 * the composer footer. Owns its slide/fade animation and the CAMERA permission launchers
 * for the Photo/Video buttons.
 *
 * @param buttonHeight footer height used to offset the bar so it sits directly above it.
 * @param onDismiss invoked before any action fires (caller sets `showAttachmentMenu = false`).
 * @param onOpenPhotoCamera / onOpenVideoCamera invoked only once CAMERA permission is granted.
 */
@Composable
fun AttachmentMenuBar(
    visible: Boolean,
    buttonHeight: Dp,
    onDismiss: () -> Unit,
    onPickImageVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onOpenPhotoCamera: () -> Unit,
    onOpenVideoCamera: () -> Unit,
    onPickLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // Permission launchers: on grant, open the corresponding in-app overlay.
    val photoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onOpenPhotoCamera() }
    val videoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onOpenVideoCamera() }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(initialAlpha = 1f, animationSpec = tween(durationMillis = scaledTweenMs(120))),
        exit = fadeOut(targetAlpha = 1f, animationSpec = tween(durationMillis = scaledTweenMs(120)))
    ) {
        val attachmentBarSlideOffsetPx = transition.animateFloat(
            transitionSpec = {
                if (initialState == EnterExitState.PreEnter && targetState == EnterExitState.Visible) {
                    // ENTER: slide in first
                    tween(durationMillis = scaledTweenMs(120))
                } else {
                    // EXIT: wait for buttons to fade out, then slide down
                    tween(durationMillis = scaledTweenMs(120), delayMillis = scaledTweenMs(500))
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
                    tween(durationMillis = scaledTweenMs(500), delayMillis = scaledTweenMs(120))
                } else {
                    // EXIT: buttons fade out immediately
                    tween(durationMillis = scaledTweenMs(500))
                }
            },
            label = "attachmentButtonsAlpha"
        ) { state ->
            if (state == EnterExitState.Visible) 1f else 0f
        }
        // Self-anchor to the bottom (same pattern as MessageMenuBar) so this works whether the
        // caller hands us a BoxScope or not — no caller-side Modifier.align needed.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .zIndex(5f),
            contentAlignment = Alignment.BottomStart
        ) {
          Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // Position menu right above footer (footer height = buttonHeight + 24.dp padding)
                    translationY = -with(density) { (buttonHeight + 24.dp).toPx() } + attachmentBarSlideOffsetPx.value
                }
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
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AttachmentMenuChip(
                        icon = Icons.Filled.Folder,
                        label = "File",
                        contentDescription = "Files",
                        buttonsAlpha = attachmentButtonsAlpha.value
                    ) {
                        onDismiss()
                        // SAF document picker — grants per-URI read access via the
                        // result, so no storage permission is needed.
                        onPickFile()
                    }

                    AttachmentMenuChip(
                        icon = Icons.Filled.AudioFile,
                        label = "Audio",
                        contentDescription = "Audio",
                        buttonsAlpha = attachmentButtonsAlpha.value
                    ) {
                        onDismiss()
                        // SAF picker — per-URI read grant, no storage permission needed.
                        onPickAudio()
                    }

                    AttachmentMenuChip(
                        icon = Icons.Filled.Image,
                        label = "Image/Video",
                        contentDescription = "Images & Videos",
                        buttonsAlpha = attachmentButtonsAlpha.value
                    ) {
                        onDismiss()
                        // Android Photo Picker — no permission needed, consistent
                        // Photo/Album sheet for both images and videos.
                        onPickImageVideo()
                    }

                    // Photo option (opens in-app snapping overlay) with expressive shape morph
                    val photoInteractionSource = remember { MutableInteractionSource() }
                    val photoPressed by photoInteractionSource.collectIsPressedAsState()
                    val photoShapePercent by animateFloatAsState(
                        targetValue = if (photoPressed) 50f else 29f, // 50 = circle on press, 29 ~ RoundedCornerShape(16.dp) on 56dp
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = scaledStiffness(Spring.StiffnessMedium)
                        ),
                        label = "photoShapeMorph"
                    )
                    AttachmentMenuChip(
                        icon = Icons.Filled.CameraAlt,
                        label = "Photo",
                        contentDescription = "Photo",
                        buttonsAlpha = attachmentButtonsAlpha.value,
                        shape = RoundedCornerShape(percent = photoShapePercent.toInt()),
                        interactionSource = photoInteractionSource
                    ) {
                        // Close attach menu and open in-app camera overlay, but only once
                        // CAMERA permission is granted — otherwise the CameraX preview binds
                        // without permission and shows a black frame while it retries for 10s.
                        onDismiss()
                        if (hasCameraPermission()) onOpenPhotoCamera()
                        else photoPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }

                    AttachmentMenuChip(
                        icon = Icons.Filled.Videocam,
                        label = "Video",
                        contentDescription = "Video",
                        buttonsAlpha = attachmentButtonsAlpha.value
                    ) {
                        onDismiss()
                        if (hasCameraPermission()) onOpenVideoCamera()
                        else videoPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }

                    AttachmentMenuChip(
                        icon = Icons.Filled.LocationOn,
                        label = "Location",
                        contentDescription = "Location",
                        buttonsAlpha = attachmentButtonsAlpha.value
                    ) {
                        onDismiss()
                        onPickLocation()
                    }
                }
            }
          }
        }
    }
}

/** A single labeled chip in [AttachmentMenuBar]: a rounded 56dp surface + icon + caption. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.AttachmentMenuChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    buttonsAlpha: Float,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f)
    ) {
        val fallbackInteractionSource = remember { MutableInteractionSource() }
        Surface(
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            shape = shape,
            tonalElevation = 1.dp,
            modifier = Modifier.size(56.dp)
        ) {
            IconButton(
                onClick = onClick,
                interactionSource = interactionSource ?: fallbackInteractionSource,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alpha(buttonsAlpha)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * Full-frame in-app photo capture overlay (CameraX). Owns flash/lens/capture state.
 *
 * @param onCaptured invoked with the saved photo URI (isVideo = false). The caller wires
 *   this into its media-preview pipeline; [onDismiss] then closes the overlay.
 */
@Composable
fun InAppCameraOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCaptured: (uri: Uri, isVideo: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var flashMode by rememberSaveable { mutableStateOf(CameraFlashMode.OFF) }
    var useFrontCamera by rememberSaveable { mutableStateOf(false) }
    var imageCaptureState by remember { mutableStateOf<ImageCapture?>(null) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = scaledStiffness(Spring.StiffnessMedium)
            )
        ) + scaleIn(
            initialScale = 0.88f,
            transformOrigin = TransformOrigin(0.5f, 1f),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = scaledStiffness(Spring.StiffnessMedium)
            )
        ),
        exit = fadeOut(
            animationSpec = tween(scaledTweenMs(160), easing = FastOutSlowInEasing)
        ) + scaleOut(
            targetScale = 0.88f,
            transformOrigin = TransformOrigin(0.5f, 1f),
            animationSpec = tween(scaledTweenMs(180), easing = FastOutSlowInEasing)
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
                        onImageCaptureReady = { imageCaptureState = it }
                    )
                }
            }

            val animatedRotation by animateFloatAsState(
                targetValue = cameraOrientation.iconAngle,
                animationSpec = tween(durationMillis = scaledTweenMs(250), easing = FastOutSlowInEasing),
                label = "cameraControlsRotation"
            )

            // Top-left: close (cancel) button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 32.dp)
                    .size(44.dp)
                    .graphicsLayer { rotationZ = animatedRotation }
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
                    flashMode = when (flashMode) {
                        CameraFlashMode.OFF -> CameraFlashMode.AUTO
                        CameraFlashMode.AUTO -> CameraFlashMode.ON
                        CameraFlashMode.ON -> CameraFlashMode.OFF
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 32.dp)
                    .size(44.dp)
                    .graphicsLayer { rotationZ = animatedRotation }
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        CameraFlashMode.OFF -> Icons.Filled.FlashOff
                        CameraFlashMode.AUTO -> Icons.Filled.FlashAuto
                        CameraFlashMode.ON -> Icons.Filled.FlashOn
                    },
                    contentDescription = when (flashMode) {
                        CameraFlashMode.OFF -> "Flash off"
                        CameraFlashMode.AUTO -> "Flash auto"
                        CameraFlashMode.ON -> "Flash on"
                    },
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
                    .padding(bottom = 80.dp)
                    .size(72.dp)
                    .graphicsLayer { rotationZ = animatedRotation },
                tonalElevation = 4.dp
            ) {
                IconButton(
                    onClick = {
                        val imageCapture = imageCaptureState
                        if (imageCapture == null) {
                            Log.w("Andromuks", "CameraX ImageCapture not ready yet")
                            return@IconButton
                        }

                        // Align capture orientation with device rotation so saved image matches preview
                        imageCapture.targetRotation = cameraOrientation.surfaceRotation
                        imageCapture.flashMode = when (flashMode) {
                            CameraFlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                            CameraFlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                            CameraFlashMode.ON -> ImageCapture.FLASH_MODE_ON
                        }

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
                                    onCaptured(savedUri, false)
                                    onDismiss()
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
                    .graphicsLayer { rotationZ = animatedRotation },
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
}

/**
 * Full-frame in-app video capture overlay (CameraX). Owns flash/lens/recording state and
 * stops any in-flight recording when disposed.
 *
 * @param onCaptured invoked with the recorded video URI (isVideo = true). [onDismiss] then
 *   closes the overlay.
 */
@Composable
fun InAppVideoOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCaptured: (uri: Uri, isVideo: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var flashMode by rememberSaveable { mutableStateOf(CameraFlashMode.OFF) }
    var useFrontCamera by rememberSaveable { mutableStateOf(false) }
    var videoCaptureState by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var recordingStartTime by remember { mutableStateOf<Long?>(null) }
    var recordingElapsedSeconds by remember { mutableStateOf(0) }

    // Safety net: if the overlay is disposed (e.g. nav away) mid-recording, stop it.
    DisposableEffect(Unit) {
        onDispose { activeRecording?.stop() }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = scaledStiffness(Spring.StiffnessMedium)
            )
        ) + scaleIn(
            initialScale = 0.88f,
            transformOrigin = TransformOrigin(0.5f, 1f),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = scaledStiffness(Spring.StiffnessMedium)
            )
        ),
        exit = fadeOut(
            animationSpec = tween(scaledTweenMs(160), easing = FastOutSlowInEasing)
        ) + scaleOut(
            targetScale = 0.88f,
            transformOrigin = TransformOrigin(0.5f, 1f),
            animationSpec = tween(scaledTweenMs(180), easing = FastOutSlowInEasing)
        )
    ) {
        val cameraOrientation = rememberCameraOrientation()

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
                key(useFrontCamera) {
                    InAppVideoPreview(
                        modifier = Modifier.fillMaxSize(),
                        useFrontCamera = useFrontCamera,
                        onVideoCaptureReady = { videoCaptureState = it }
                    )
                }
            }

            val animatedRotation by animateFloatAsState(
                targetValue = cameraOrientation.iconAngle,
                animationSpec = tween(durationMillis = scaledTweenMs(250), easing = FastOutSlowInEasing),
                label = "videoControlsRotation"
            )

            // Update record elapsed time every second while recording
            LaunchedEffect(activeRecording, recordingStartTime) {
                if (activeRecording == null || recordingStartTime == null) return@LaunchedEffect
                val start = recordingStartTime!!
                while (activeRecording != null) {
                    kotlinx.coroutines.delay(1000)
                    recordingElapsedSeconds = ((System.currentTimeMillis() - start) / 1000).toInt()
                }
            }

            IconButton(
                onClick = {
                    activeRecording?.stop()
                    activeRecording = null
                    recordingStartTime = null
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 32.dp)
                    .size(44.dp)
                    .graphicsLayer { rotationZ = animatedRotation }
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
                    flashMode = when (flashMode) {
                        CameraFlashMode.OFF -> CameraFlashMode.AUTO
                        CameraFlashMode.AUTO -> CameraFlashMode.ON
                        CameraFlashMode.ON -> CameraFlashMode.OFF
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 32.dp)
                    .size(44.dp)
                    .graphicsLayer { rotationZ = animatedRotation }
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        CameraFlashMode.OFF -> Icons.Filled.FlashOff
                        CameraFlashMode.AUTO -> Icons.Filled.FlashAuto
                        CameraFlashMode.ON -> Icons.Filled.FlashOn
                    },
                    contentDescription = when (flashMode) {
                        CameraFlashMode.OFF -> "Flash off"
                        CameraFlashMode.AUTO -> "Flash auto"
                        CameraFlashMode.ON -> "Flash on"
                    },
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Static record length (bottom-left) while recording
            if (activeRecording != null) {
                Text(
                    text = "${recordingElapsedSeconds / 60}:${(recordingElapsedSeconds % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, bottom = 80.dp)
                        .graphicsLayer { rotationZ = animatedRotation }
                )
            }

            // Record button (bottom-center)
            Surface(
                shape = CircleShape,
                color = if (activeRecording != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .size(72.dp)
                    .graphicsLayer { rotationZ = animatedRotation },
                tonalElevation = 4.dp
            ) {
                IconButton(
                    onClick = {
                        val vc = videoCaptureState
                        if (vc == null) {
                            Log.w("Andromuks", "VideoCapture not ready yet")
                            return@IconButton
                        }
                        if (activeRecording != null) {
                            activeRecording?.stop()
                            activeRecording = null
                            recordingStartTime = null
                            recordingElapsedSeconds = 0
                            return@IconButton
                        }
                        vc.targetRotation = cameraOrientation.surfaceRotation
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
                                    recordingStartTime = null
                                    recordingElapsedSeconds = 0
                                    onCaptured(videoFile.toUri(), true)
                                    onDismiss()
                                }
                                else -> {}
                            }
                        }
                        activeRecording = rec
                        recordingStartTime = System.currentTimeMillis()
                        recordingElapsedSeconds = 0
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = if (activeRecording != null) "Stop recording" else "Start recording",
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
                    .graphicsLayer { rotationZ = animatedRotation },
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
}

@Composable
private fun InAppCameraPreview(
    modifier: Modifier = Modifier,
    useFrontCamera: Boolean,
    onImageCaptureReady: (ImageCapture) -> Unit
) {
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
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .build()
                        )
                        .build()
                        .also { p -> p.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageCapture = ImageCapture.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .build()
                        )
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
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .build()
                        )
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
