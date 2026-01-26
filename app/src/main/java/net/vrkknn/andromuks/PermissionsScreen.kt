package net.vrkknn.andromuks

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Power
import net.vrkknn.andromuks.utils.AutoStartPermissionHelper
import net.vrkknn.andromuks.BuildConfig


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onPermissionsGranted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Track permission states
    var notificationPermissionGranted by remember { mutableStateOf(checkNotificationPermission(context)) }
    var batteryOptimizationDisabled by remember { mutableStateOf(checkBatteryOptimization(context)) }
    
    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionGranted = isGranted
        if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Notification permission result: $isGranted")
        checkAndProceed(notificationPermissionGranted, batteryOptimizationDisabled, onPermissionsGranted)
    }
    
    // Battery optimization settings launcher
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-check battery optimization status after returning from settings
        batteryOptimizationDisabled = checkBatteryOptimization(context)
        if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Battery optimization disabled: $batteryOptimizationDisabled")
        checkAndProceed(notificationPermissionGranted, batteryOptimizationDisabled, onPermissionsGranted)
    }
    
    // Check if all permissions are already granted on first composition
    LaunchedEffect(Unit) {
        notificationPermissionGranted = checkNotificationPermission(context)
        batteryOptimizationDisabled = checkBatteryOptimization(context)
        
        if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Initial permission check - notifications: $notificationPermissionGranted, battery: $batteryOptimizationDisabled")
        
        // If notification permission is granted, proceed immediately (battery exemption is optional)
        if (notificationPermissionGranted) {
            if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Notification permission already granted, proceeding")
            onPermissionsGranted()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions Required") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "To provide real-time messaging, Andromuks needs notification permission. Battery optimization exemption is optional but recommended for instant delivery.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            
            // Notification Permission Card
            PermissionCard(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                description = "Receive message notifications even when the app is closed",
                isGranted = notificationPermissionGranted,
                onRequestClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Requesting notification permission")
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // Notifications are auto-granted on Android 12 and below
                        notificationPermissionGranted = true
                        checkAndProceed(notificationPermissionGranted, batteryOptimizationDisabled, onPermissionsGranted)
                    }
                }
            )
            
            // Battery Optimization Card (Optional)
            PermissionCard(
                icon = Icons.Default.BatteryFull,
                title = "Battery Optimization Exemption (Optional)",
                description = "Keep connection alive in the background for instant message delivery. Not required if you prefer Android to manage battery usage (FCM notifications will still work).",
                isGranted = batteryOptimizationDisabled,
                onRequestClick = {
                    if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Requesting battery optimization exemption")
                    requestBatteryOptimizationExemption(context, batteryOptimizationLauncher)
                }
            )
            
            // Auto-start Permission Card (device-specific, informational only)
            if (AutoStartPermissionHelper.isAutoStartPermissionNeeded()) {
                AutoStartInfoCard(
                    context = context,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Continue button (only requires notification permission; battery exemption is optional)
            if (notificationPermissionGranted) {
                Button(
                    onClick = {
                        if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Continue button clicked")
                        onPermissionsGranted()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                ) {
                    Text("Continue")
                }
            } else {
                Text(
                    text = "Please grant notification permission to continue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequestClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                if (isGranted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Granted",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (!isGranted) {
                Button(
                    onClick = onRequestClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@Composable
private fun AutoStartInfoCard(
    context: Context,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Power,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${AutoStartPermissionHelper.getAutoStartPermissionName()} (Optional)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            Text(
                text = AutoStartPermissionHelper.getAutoStartDescription(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            OutlinedButton(
                onClick = {
                    if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Opening auto-start settings")
                    AutoStartPermissionHelper.openAutoStartSettings(context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Settings")
            }
            
            Text(
                text = "This is optional but recommended for ${AutoStartPermissionHelper.getManufacturer()} devices to ensure reliable background operation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

/**
 * Check if notification permission is granted
 */
private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        // Notifications are auto-granted on Android 12 and below
        true
    }
}

/**
 * Check if battery optimization is disabled for this app
 */
private fun checkBatteryOptimization(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Request battery optimization exemption
 */
private fun requestBatteryOptimizationExemption(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        launcher.launch(intent)
    } catch (e: Exception) {
        Log.e("PermissionsScreen", "Failed to request battery optimization exemption", e)
        // Fallback: Open battery optimization settings
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            launcher.launch(intent)
        } catch (e2: Exception) {
            Log.e("PermissionsScreen", "Failed to open battery optimization settings", e2)
        }
    }
}

/**
 * Check if required permissions are granted and proceed
 * Only notification permission is required; battery exemption is optional
 */
private fun checkAndProceed(
    notificationGranted: Boolean,
    batteryOptimizationDisabled: Boolean,
    onPermissionsGranted: () -> Unit
) {
    if (notificationGranted) {
        if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Notification permission granted, proceeding (battery exemption: $batteryOptimizationDisabled)")
        onPermissionsGranted()
    } else {
        if (BuildConfig.DEBUG) Log.d("PermissionsScreen", "Notification permission not granted yet - notifications: $notificationGranted, battery: $batteryOptimizationDisabled")
    }
}

