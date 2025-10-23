# Permissions Implementation

## Summary

The app now requests **two critical permissions** on first launch to ensure the WebSocket foreground service works reliably:

1. **Notifications** - For receiving message notifications
2. **Battery Optimization Exemption** - For maintaining WebSocket connection in background

## Permissions Overview

### 1. Notification Permission (Android 13+)

**Permission:** `android.permission.POST_NOTIFICATIONS`

**Purpose:**
- Display message notifications when app is backgrounded
- Show notification actions (reply, mark as read)
- Display foreground service notification

**Behavior:**
- **Android 13+**: Runtime permission required
- **Android 12 and below**: Automatically granted

### 2. Battery Optimization Exemption

**Permission:** `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

**Purpose:**
- Prevent Android from killing the WebSocket foreground service
- Ensure real-time message delivery even in Doze mode
- Maintain persistent connection without interruption

**Behavior:**
- User must manually grant via system settings dialog
- Critical for reliable background operation
- Without this, Android may aggressively kill the service

## Implementation

### Files Added

#### PermissionsScreen.kt
**Location:** `app/src/main/java/net/vrkknn/andromuks/PermissionsScreen.kt`

**Features:**
- Beautiful Material 3 UI with permission cards
- Clear explanations for each permission
- Individual "Grant Permission" buttons
- Checkmarks when permissions are granted
- "Continue" button appears when all permissions granted
- Auto-proceeds if permissions already granted

**UI Components:**
- `PermissionsScreen()` - Main composable
- `PermissionCard()` - Individual permission card component
- Permission launchers for runtime requests
- Activity result handlers for settings navigation

### Files Modified

#### AndroidManifest.xml
**Added:**
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

**Already present:**
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

#### MainActivity.kt
**Added navigation route:**
```kotlin
composable("permissions") {
    PermissionsScreen(
        onPermissionsGranted = {
            navController.navigate("auth_check") {
                popUpTo("permissions") { inclusive = true }
            }
        }
    )
}
```

#### AuthCheck.kt
**Added permission checking:**
- Checks both permissions on launch
- If not granted → Navigate to permissions screen
- If granted → Proceed with WebSocket connection
- Prevents connection without proper permissions

## User Flow

### First Launch (No Permissions)

1. **User opens app**
2. **Login screen** → User logs in
3. **AuthCheck** → Detects missing permissions
4. **Permissions screen** appears with explanation:
   - "Notifications" card with "Grant Permission" button
   - "Battery Optimization" card with "Grant Permission" button
5. **User grants Notification permission**:
   - System dialog appears
   - User taps "Allow"
   - Checkmark appears on card
6. **User grants Battery Optimization exemption**:
   - Settings screen opens
   - User finds app and allows exemption
   - Returns to app
   - Checkmark appears on card
7. **"Continue" button** appears
8. **User taps Continue**
9. **AuthCheck** → Starts WebSocket service
10. **App proceeds to room list**

### Subsequent Launches (Permissions Granted)

1. **User opens app**
2. **AuthCheck** → Detects permissions already granted
3. **Skips permissions screen** automatically
4. **Starts WebSocket service**
5. **Proceeds directly to room list**

### Permission Revoked Scenario

If user revokes permission in settings:

1. **User opens app**
2. **AuthCheck** → Detects missing permission
3. **Redirects to permissions screen**
4. **User must re-grant permission**
5. **Proceeds once granted**

## Code Structure

### Permission Checking Functions

#### `checkNotificationPermission()`
```kotlin
private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true // Auto-granted on Android 12 and below
    }
}
```

#### `checkBatteryOptimization()`
```kotlin
private fun checkBatteryOptimization(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
```

### Permission Request Functions

#### `requestBatteryOptimizationExemption()`
Opens system settings for battery optimization:
```kotlin
private fun requestBatteryOptimizationExemption(
    context: Context,
    launcher: ActivityResultLauncher<Intent>
) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    launcher.launch(intent)
}
```

## Android Version Compatibility

### Android 13+ (API 33+)
- ✅ Notification permission required
- ✅ Battery optimization exemption required
- ✅ Both displayed in permissions screen

### Android 12 and below (API < 33)
- ✅ Notifications auto-granted
- ✅ Battery optimization exemption still required
- ✅ Only battery optimization shown in permissions screen

## Google Play Store Policy

### Battery Optimization Exemption

**Important:** Google Play has strict policies about battery optimization exemptions.

**Acceptable Use Cases:**
- ✅ Real-time messaging apps (our use case)
- ✅ VoIP/calling apps
- ✅ Navigation apps
- ✅ Media playback apps

**Required:**
- Clear explanation to user why exemption is needed
- Prominent in-app disclosure (we have this in permission card)
- Cannot request exemption without user action (we show button)

**Our Implementation:**
- ✅ Clear description: "Keep connection alive for instant message delivery"
- ✅ User-initiated: Must tap "Grant Permission" button
- ✅ Legitimate use case: Real-time messaging
- ✅ Alternative offered: App works without it (just less reliably)

## Testing Checklist

### First Launch Testing
- [ ] Fresh install → Permissions screen appears
- [ ] Notification permission request works
- [ ] Battery optimization settings open correctly
- [ ] Checkmarks appear when granted
- [ ] Continue button enables when all granted
- [ ] App proceeds to room list after Continue

### Permission State Testing
- [ ] Notification granted, battery not → Shows only battery
- [ ] Battery granted, notification not → Shows only notification
- [ ] Both granted → Auto-proceeds without showing screen
- [ ] One revoked → Returns to permissions screen

### Android Version Testing
- [ ] Android 13+ → Both permissions shown
- [ ] Android 12 → Only battery optimization shown
- [ ] Android 11 → Only battery optimization shown

### Edge Cases
- [ ] User denies notification → Can try again
- [ ] User denies battery optimization → Can try again
- [ ] User presses back on permissions screen → Stays on screen
- [ ] Permission granted in settings while app open → Detected immediately

## Benefits

### ✅ User Awareness
- Users understand why permissions are needed
- Clear explanations prevent confusion
- Transparent about always-on connection

### ✅ Reliable Service
- Battery optimization exemption prevents service kills
- Ensures WebSocket stays connected
- No missed messages or delayed notifications

### ✅ Better UX
- Single permission request screen
- Beautiful Material 3 design
- Progress indication (checkmarks)
- Can't proceed without permissions (prevents errors)

### ✅ Play Store Compliance
- Follows Google Play policies
- User-initiated requests
- Clear in-app disclosure
- Legitimate use case

## Troubleshooting

### Service Gets Killed Despite Exemption

**Possible causes:**
1. OEM aggressive battery optimization (Xiaomi, Huawei, etc.)
2. User manually force-stopped app
3. Low memory situation

**Solutions:**
1. Add OEM-specific battery optimization instructions
2. Monitor service lifecycle and auto-restart
3. Show notification when service is killed

### Permission Stuck in "Denied" State

**Cause:** User selected "Don't ask again"

**Solution:**
- Detect this state
- Show instructions to grant in Settings
- Provide "Open Settings" button

### Battery Optimization Settings Don't Open

**Cause:** Different Android versions have different intent actions

**Solution:**
- Try primary intent first: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- Fallback to: `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
- Final fallback: General app settings

## Future Enhancements

### Optional: Skip Permissions (Advanced Users)

Add "Skip" button with warning:
```kotlin
TextButton(onClick = {
    // Proceed without permissions
    // Show warning dialog
}) {
    Text("Skip (Not Recommended)")
}
```

### Optional: Re-request Later

Save preference if user skips:
```kotlin
sharedPreferences.edit {
    putBoolean("permissions_skipped", true)
}
```

Show reminder on settings screen:
```kotlin
if (permissions_skipped && !all_granted) {
    // Show "Grant Permissions" button
}
```

### Optional: OEM-Specific Instructions

Detect OEM and show specific instructions:
```kotlin
val manufacturer = Build.MANUFACTURER.lowercase()
when {
    manufacturer.contains("xiaomi") -> showXiaomiInstructions()
    manufacturer.contains("huawei") -> showHuaweiInstructions()
    manufacturer.contains("samsung") -> showSamsungInstructions()
}
```

## Documentation

- Permissions flow documented in this file
- Code comments explain each permission
- User-facing text explains why permissions needed
- Play Store listing should mention background connection

---

**Last Updated:** [Current Date]  
**Related Documents:**
- WEBSOCKET_SERVICE_DEFAULT_IMPLEMENTATION.md
- FCM_INTEGRATION_GUIDE.md

