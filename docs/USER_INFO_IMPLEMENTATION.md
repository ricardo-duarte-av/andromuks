# User Info Screen Implementation

## Overview
This implementation adds a comprehensive user information screen that displays detailed information about Matrix users, including their profile, encryption devices, and shared rooms.

## Files Created/Modified

### New File: `app/src/main/java/net/vrkknn/andromuks/utils/UserInfo.kt`
This file contains the complete user info screen UI implementation with the following components:

#### Data Classes:
- **`UserEncryptionInfo`** - Holds encryption and device tracking information
- **`DeviceInfo`** - Represents a single Matrix device with signing keys and trust state
- **`UserProfileInfo`** - Complete user profile combining all information sources
- **`RoomMember`** - Room member information (reused from RoomInfo.kt pattern)

#### Composable Functions:
- **`UserInfoScreen`** - Main screen displaying user information
- **`SharedRoomItem`** - Individual shared room list item
- **`DeviceListDialog`** - Floating dialog showing encryption devices
- **`DeviceInfoCard`** - Individual device information card

### Modified File: `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`

#### Added Request Tracking Maps:
```kotlin
private val userEncryptionInfoRequests = mutableMapOf<Int, (UserEncryptionInfo?, String?) -> Unit>()
private val mutualRoomsRequests = mutableMapOf<Int, (List<String>?, String?) -> Unit>()
private val trackDevicesRequests = mutableMapOf<Int, (UserEncryptionInfo?, String?) -> Unit>()
private val fullUserInfoCallbacks = mutableMapOf<Int, (JSONObject?) -> Unit>()
```

#### Added Public Functions:
- **`requestUserEncryptionInfo(userId, callback)`** - Requests encryption info for a user
- **`requestMutualRooms(userId, callback)`** - Requests shared rooms
- **`trackUserDevices(userId, callback)`** - Starts tracking user's devices
- **`requestFullUserInfo(userId, callback)`** - Requests all user info at once (recommended)

#### Added Private Handler Functions:
- **`handleUserEncryptionInfoResponse()`** - Handles encryption info responses
- **`handleMutualRoomsResponse()`** - Handles mutual rooms responses
- **`handleTrackDevicesResponse()`** - Handles track devices responses
- **`parseUserEncryptionInfo()`** - Parses encryption info JSON

## WebSocket Commands

### 1. get_profile_encryption_info
**Command:**
```json
{
  "command": "get_profile_encryption_info",
  "request_id": 1282,
  "data": {
    "user_id": "@user:matrix.org"
  }
}
```

**Response (not tracking):**
```json
{
  "command": "response",
  "request_id": 1282,
  "data": {
    "devices_tracked": false,
    "devices": null,
    "master_key": "",
    "first_master_key": "",
    "user_trusted": false,
    "errors": null
  }
}
```

**Response (tracking):**
```json
{
  "command": "response",
  "request_id": 1304,
  "data": {
    "devices_tracked": true,
    "devices": [
      {
        "device_id": "BCSHKBHUPF",
        "name": "BCSHKBHUPF",
        "identity_key": "...",
        "signing_key": "...",
        "fingerprint": "Q7N6 jKTC gFxe...",
        "trust_state": "cross-signed-tofu"
      }
    ],
    "master_key": "B4m6 w9Yz cBwf...",
    "first_master_key": "B4m6 w9Yz cBwf...",
    "user_trusted": false,
    "errors": null
  }
}
```

### 2. get_mutual_rooms
**Command:**
```json
{
  "command": "get_mutual_rooms",
  "request_id": 1305,
  "data": {
    "user_id": "@user:matrix.org"
  }
}
```

**Response:**
```json
{
  "command": "response",
  "request_id": 1305,
  "data": ["!roomId1:server.com", "!roomId2:server.com"]
}
```

### 3. track_user_devices
**Command:**
```json
{
  "command": "track_user_devices",
  "request_id": 1291,
  "data": {
    "user_id": "@user:matrix.org"
  }
}
```

**Response:**
Same format as `get_profile_encryption_info` with `devices_tracked: true`

### 4. get_profile
This command already existed in the codebase.

**Command:**
```json
{
  "command": "get_profile",
  "request_id": 4,
  "data": {
    "user_id": "@user:matrix.org"
  }
}
```

**Response:**
```json
{
  "command": "response",
  "request_id": 4,
  "data": {
    "avatar_url": "mxc://...",
    "displayname": "John Doe",
    "us.cloke.msc4175.tz": "Europe/London"
  }
}
```

## UI Features

### Main Screen Layout:
1. **User Avatar** - Large 120dp avatar at the top
2. **Display Name** - User's display name (or Matrix ID if no display name)
3. **Matrix User ID** - Full Matrix user ID
4. **Timezone Clock** - Live updating clock showing current time in user's timezone (if available)
5. **Device List Button** - Dynamic button that shows:
   - "Track Device List" - If not tracking devices yet
   - "Device List (N)" - If already tracking, shows device count
6. **Shared Rooms List** - Scrollable list of rooms you have in common

### Device List Dialog:
When clicking the Device List button, a floating dialog shows:
- **Master Key Information** - User's master cross-signing key
- **User Trust Status** - Whether the user is trusted
- **Device List** - Scrollable list of all devices with:
  - Device ID and name
  - Trust state badge (color-coded)
  - Fingerprint
  - Identity and signing keys

### Device Trust States:
- **verified** - Green container (primary)
- **cross-signed-tofu** - Yellow container (secondary)
- **other** - Red container (error)

## Navigation Integration

### Files Modified for Click Navigation

1. **`utils/ReceiptFunctions.kt`**
   - Added `onUserClick: (String) -> Unit` parameter to `InlineReadReceiptAvatars`
   - Added `onUserClick` parameter to `ReadReceiptDetailsDialog`
   - Made `ReadReceiptItem` clickable to navigate to user info
   - Clicking any user in the read receipts dialog navigates to their user info

2. **`utils/RoomInfo.kt`**
   - Added `onUserClick` parameter to `RoomMemberItem`
   - Made member list items clickable
   - Added navigation code to navigate to user info when clicking members

3. **`RoomTimelineScreen.kt`**
   - Added `onUserClick` parameter to `TimelineEventItem`
   - Made user avatars clickable (wrapped in Box with clickable modifier)
   - Made user display names clickable (added clickable modifier to Text)
   - Passed `onUserClick` callback to all `InlineReadReceiptAvatars` calls

### Click Targets

The user info screen can now be accessed by clicking:

1. **In RoomTimelineScreen:**
   - User avatar (for non-consecutive messages)
   - User display name/username in message header
   - Read receipt avatars (opens dialog, then click user to navigate)
   - Read receipt "+" indicator (opens dialog with all users)

2. **In RoomInfo:**
   - Any user in the member list (avatar or name)

3. **In Read Receipts Dialog:**
   - Any user row in the "Read by" dialog

### Navigation Route Setup

✅ **Already Added!** The navigation route has been added to `MainActivity.kt`:

```kotlin
composable(
    route = "user_info/{userId}",
    arguments = listOf(navArgument("userId") { type = NavType.StringType })
) { backStackEntry: NavBackStackEntry ->
    val userId = backStackEntry.arguments?.getString("userId") ?: ""
    net.vrkknn.andromuks.utils.UserInfoScreen(
        userId = userId,
        navController = navController,
        appViewModel = appViewModel,
        modifier = modifier
    )
}
```

### Navigation Code Pattern

All click handlers use this pattern for safe URL encoding:

```kotlin
onUserClick = { userId ->
    navController.navigate("user_info/${java.net.URLEncoder.encode(userId, "UTF-8")}")
}
```

This ensures Matrix user IDs (e.g., `@user:server.com`) are properly encoded for navigation.

## API Usage Example

```kotlin
// Request full user info (recommended - fetches all 3 commands at once)
appViewModel.requestFullUserInfo("@user:matrix.org") { userInfo, error ->
    if (error != null) {
        // Handle error
        Log.e("TAG", "Error: $error")
    } else {
        // Use userInfo
        Log.d("TAG", "Display name: ${userInfo?.displayName}")
        Log.d("TAG", "Shared rooms: ${userInfo?.mutualRooms?.size}")
    }
}

// Or request individual components:
appViewModel.requestUserEncryptionInfo("@user:matrix.org") { encInfo, error ->
    // Handle encryption info
}

appViewModel.requestMutualRooms("@user:matrix.org") { rooms, error ->
    // Handle mutual rooms
}

appViewModel.trackUserDevices("@user:matrix.org") { encInfo, error ->
    // Start tracking and handle device list
}
```

## Features

✅ **Automatic timezone detection** - Shows live clock in user's timezone  
✅ **Device tracking** - Track and view all user devices with encryption info  
✅ **Shared rooms** - See all rooms you have in common  
✅ **Avatar fallback** - Generates colored fallback avatars if no avatar set  
✅ **Material 3 design** - Modern UI following Material Design 3 guidelines  
✅ **Responsive layout** - Adapts to different screen sizes  
✅ **Error handling** - Graceful error messages and loading states  
✅ **Device search** - Scroll through large device lists efficiently  

## Technical Notes

- The implementation uses Kotlin coroutines for async operations
- All WebSocket commands are tracked with unique request IDs
- Timezone display uses `java.time` API (requires Android 8.0+)
- Device list dialog is designed to handle hundreds of devices efficiently using LazyColumn
- All data classes are immutable and use Kotlin data classes for proper equality checks
- The UI automatically updates when data is received via callbacks

## Future Enhancements

Potential improvements that could be added:
- Device verification UI (verify/unverify devices)
- Direct message button to quickly start DM with user
- Block/unblock user functionality
- Copy to clipboard for keys and IDs
- Export device fingerprints
- Show last seen time for devices

