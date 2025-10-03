# FCM Integration Guide for Andromuks

This guide explains how to integrate the FCM notification system with the Gomuks Backend architecture.

## Architecture Overview

The system follows this flow:
1. **Andromuks Client** → **Gomuks Backend** (via WebSocket)
2. **Gomuks Backend** → **FCM Push Gateway** 
3. **FCM Push Gateway** → **FCM** → **Android Client**

## Overview

The FCM implementation registers with the Gomuks Backend via WebSocket commands:
1. FCM tokens are stored and sent to Gomuks Backend via WebSocket
2. Push registration uses the `register_push` WebSocket command
3. Registration timing follows the 12-hour interval pattern

## Key Components

### 1. WebClientPushIntegration
Handles device management and timing for Gomuks Backend:
- Device ID management
- Push encryption key generation
- Registration timing (12-hour intervals)

### 2. Updated FCMNotificationManager
Now stores tokens for Gomuks Backend registration:
- Stores FCM tokens in SharedPreferences
- Prepares tokens for WebSocket registration
- Maintains registration status tracking

### 3. AppViewModel Integration
Provides WebSocket-based registration with Gomuks Backend:
- `getFCMTokenForGomuksBackend()` - Get stored FCM token
- `registerFCMWithGomuksBackend()` - Send registration via WebSocket
- `shouldRegisterPush()` - Check if registration is needed (time-based)
- `getDeviceID()` - Get device identifier

## Integration Steps

### 1. Automatic Registration
FCM registration happens automatically when the Gomuks Backend connection is established:

```kotlin
// In AppViewModel.onInitComplete()
if (shouldRegisterPush()) {
    registerFCMWithGomuksBackend()
}
```

### 2. WebSocket Command Format
The registration is sent as a WebSocket command using data from Gomuks Backend:

```kotlin
sendWebSocketCommand("register_push", requestId, mapOf(
    "data" to mapOf(
        "token" to fcmToken,           // From FCM
        "device_id" to deviceId,       // From client_state WebSocket message
        "encryption" to mapOf(
            "key" to encryptionKey     // Generated using Android Keystore
        )
    )
))
```

**Data Sources:**
- **Token**: Received from FCM
- **Device ID**: From `client_state` WebSocket message: `{"command":"client_state","data":{"device_id":"VYTAQJDZAV"}}`
- **Encryption Key**: Generated using Android Keystore for secure storage

### 3. Response Handling
FCM registration responses are handled automatically:

```kotlin
fun handleFCMRegistrationResponse(requestId: Int, data: Any) {
    val success = data as? Boolean ?: false
    if (success) {
        markPushRegistrationCompleted()
    }
}
```

## WebSocket Command Format

### Push Registration Command
```json
{
    "command": "register_push",
    "request_id": 123,
    "data": {
        "data": {
            "token": "fcm-token-from-firebase",
            "device_id": "VYTAQJDZAV",
            "encryption": {
                "key": "base64-encoded-keystore-key"
            }
        }
    }
}
```

**Example client_state message that provides device_id:**
```json
{
    "command": "client_state",
    "request_id": 0,
    "data": {
        "is_logged_in": true,
        "is_verified": true,
        "user_id": "@test:aguiarvieira.pt",
        "device_id": "VYTAQJDZAV",
        "homeserver_url": "https://aguiarvieira.pt/"
    }
}
```

### Registration Response
```json
{
    "command": "response",
    "request_id": 123,
    "data": true
}
```

## Features Implemented

### ✅ Core FCM Functionality
- FCM token generation and storage
- Notification parsing and display
- Conversation shortcuts (Android 7.1+)
- Rich notifications with reply actions
- Notification grouping by room

### ✅ Gomuks Backend Integration
- WebSocket-based registration with Gomuks Backend
- Device ID and encryption key management
- 12-hour registration interval
- Automatic registration on connection

### ✅ Enhanced Notifications
- MessagingStyle notifications
- Avatar loading and display
- Quick reply actions
- Mark as read functionality
- Conversation grouping

## Usage Examples

### Getting FCM Token
```kotlin
val token = appViewModel.getFCMTokenForGomuksBackend()
if (token != null) {
    // Token is available for registration
}
```

### Manual FCM Registration
```kotlin
// Usually automatic, but can be triggered manually
if (appViewModel.shouldRegisterPush()) {
    appViewModel.registerFCMWithGomuksBackend()
}
```

### Checking Registration Timing
```kotlin
if (appViewModel.shouldRegisterPush()) {
    // Registration is needed
    appViewModel.registerFCMWithGomuksBackend()
}
```

## Next Steps

1. **Test FCM Flow**: Verify tokens are generated and stored correctly
2. **Test Gomuks Backend Communication**: Ensure WebSocket commands are sent properly
3. **Test Notifications**: Verify notifications appear when messages are received
4. **Configure Gomuks Backend**: Ensure your Gomuks Backend is configured with the FCM Push Gateway URL

The implementation is ready to work with your Gomuks Backend architecture!
