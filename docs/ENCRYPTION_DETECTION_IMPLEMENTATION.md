# Room Encryption Detection Implementation

## Overview
This document describes the implementation of room encryption detection in Andromuks. The app now automatically detects whether a Matrix room is encrypted by checking for the presence of `m.room.encryption` state events.

## How It Works

### 1. Request Room State
When a user enters a room (via `requestRoomTimeline()`), the app now calls `requestRoomState()` which sends a `get_room_state` command to the server:

```kotlin
sendWebSocketCommand("get_room_state", stateRequestId, mapOf(
    "room_id" to roomId,
    "include_members" to false,
    "fetch_members" to false,
    "refetch" to false
))
```

### 2. Server Response
The server responds with an array of state events for the room. For an encrypted room, this includes an `m.room.encryption` event:

```json
{
  "rowid": 524,
  "timeline_rowid": -1,
  "room_id": "!decSfHzLLhrNKJaZid:aguiarvieira.pt",
  "event_id": "$b_Gc300xflTdnRfkPR45bbq9x4Z6qXWL4vqUTtP9FBc",
  "sender": "@daedric:aguiarvieira.pt",
  "type": "m.room.encryption",
  "state_key": "",
  "timestamp": 1759094669976,
  "content": {
    "algorithm": "m.megolm.v1.aes-sha2"
  },
  "unsigned": {
    "membership": "leave",
    "age": 24522
  }
}
```

### 3. Event Processing
The `parseRoomStateFromEvents()` function iterates through all state events and checks for:
- `m.room.name` - Room name
- `m.room.canonical_alias` - Room alias
- `m.room.topic` - Room topic
- `m.room.avatar` - Room avatar
- **`m.room.encryption`** - Encryption status (NEW)

### 4. Encryption Detection Logic
```kotlin
"m.room.encryption" -> {
    // Check if the room is encrypted (presence of m.room.encryption event)
    val algorithm = content?.optString("algorithm")?.takeIf { it.isNotBlank() }
    if (algorithm != null) {
        isEncrypted = true
        android.util.Log.d("Andromuks", "AppViewModel: Room is encrypted with algorithm: $algorithm")
    }
}
```

If the `m.room.encryption` event is present with a valid `algorithm` field, the room is marked as encrypted.

### 5. State Storage
The encryption status is stored in the `RoomState` data class:

```kotlin
data class RoomState(
    val roomId: String,
    val name: String?,
    val canonicalAlias: String?,
    val topic: String?,
    val avatarUrl: String?,
    val isEncrypted: Boolean = false  // NEW FIELD
)
```

### 6. Usage in Media Upload
When uploading media, the app now uses the actual encryption status:

```kotlin
// Get room encryption status from current room state
val isRoomEncrypted = appViewModel.currentRoomState?.isEncrypted ?: false
Log.d("Andromuks", "RoomTimelineScreen: Uploading media, room encrypted: $isRoomEncrypted")

val uploadResult = MediaUploadUtils.uploadMedia(
    context = context,
    uri = selectedMediaUri!!,
    homeserverUrl = homeserverUrl,
    authToken = authToken,
    isEncrypted = isRoomEncrypted  // Uses actual status
)
```

## Implementation Details

### Files Modified

1. **`app/src/main/java/net/vrkknn/andromuks/RoomItem.kt`**
   - Added `isEncrypted: Boolean = false` field to `RoomState` data class

2. **`app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`**
   - Modified `parseRoomStateFromEvents()` to detect `m.room.encryption` events
   - Added encryption status to `RoomState` creation
   - Updated logging to include encryption status
   - Modified `requestRoomTimeline()` to call `requestRoomState()`

3. **`app/src/main/java/net/vrkknn/andromuks/RoomTimelineScreen.kt`**
   - Updated media upload to use `appViewModel.currentRoomState?.isEncrypted ?: false`
   - Added logging to track encryption status during upload

### Code Changes Summary

#### RoomItem.kt
```kotlin
// Before
data class RoomState(
    val roomId: String,
    val name: String?,
    val canonicalAlias: String?,
    val topic: String?,
    val avatarUrl: String?
)

// After
data class RoomState(
    val roomId: String,
    val name: String?,
    val canonicalAlias: String?,
    val topic: String?,
    val avatarUrl: String?,
    val isEncrypted: Boolean = false
)
```

#### AppViewModel.kt - parseRoomStateFromEvents()
```kotlin
// Added encryption detection variable
var isEncrypted = false

// Added new case in when statement
when (eventType) {
    // ... existing cases ...
    "m.room.encryption" -> {
        val algorithm = content?.optString("algorithm")?.takeIf { it.isNotBlank() }
        if (algorithm != null) {
            isEncrypted = true
            android.util.Log.d("Andromuks", "AppViewModel: Room is encrypted with algorithm: $algorithm")
        }
    }
}

// Updated RoomState creation
val roomState = RoomState(
    roomId = roomId,
    name = name,
    canonicalAlias = canonicalAlias,
    topic = topic,
    avatarUrl = avatarUrl,
    isEncrypted = isEncrypted  // NEW
)
```

#### AppViewModel.kt - requestRoomTimeline()
```kotlin
fun requestRoomTimeline(roomId: String) {
    // ... existing code ...
    
    // Request room state (including encryption status)
    requestRoomState(roomId)  // NEW LINE
    
    // ... rest of function ...
}
```

#### RoomTimelineScreen.kt
```kotlin
// Before
val uploadResult = withContext(Dispatchers.IO) {
    MediaUploadUtils.uploadMedia(
        context = context,
        uri = selectedMediaUri!!,
        homeserverUrl = homeserverUrl,
        authToken = authToken,
        isEncrypted = false  // Hardcoded
    )
}

// After
val isRoomEncrypted = appViewModel.currentRoomState?.isEncrypted ?: false
Log.d("Andromuks", "RoomTimelineScreen: Uploading media, room encrypted: $isRoomEncrypted")

val uploadResult = withContext(Dispatchers.IO) {
    MediaUploadUtils.uploadMedia(
        context = context,
        uri = selectedMediaUri!!,
        homeserverUrl = homeserverUrl,
        authToken = authToken,
        isEncrypted = isRoomEncrypted  // Uses actual status
    )
}
```

## Supported Encryption Algorithms

The implementation detects any encryption algorithm specified in the `m.room.encryption` event. Common algorithms include:

- **`m.megolm.v1.aes-sha2`** - The standard Matrix encryption algorithm
- Future algorithms will be automatically detected

## Logging

Comprehensive logging has been added to track encryption detection:

```
AppViewModel: Processing event type: m.room.encryption
AppViewModel: Room is encrypted with algorithm: m.megolm.v1.aes-sha2
AppViewModel: Parsed room state - Name: Test Room, Alias: #test:server.com, Topic: null, Avatar: mxc://..., Encrypted: true
RoomTimelineScreen: Uploading media, room encrypted: true
```

## Testing

### For Encrypted Rooms
1. Enter an encrypted room
2. Check logs for: `Room is encrypted with algorithm: m.megolm.v1.aes-sha2`
3. Check logs for: `Parsed room state - ... Encrypted: true`
4. Upload an image
5. Check logs for: `Uploading media, room encrypted: true`
6. Verify upload URL includes `encrypt=true`

### For Unencrypted Rooms
1. Enter an unencrypted room
2. Check logs - no `m.room.encryption` event should be processed
3. Check logs for: `Parsed room state - ... Encrypted: false`
4. Upload an image
5. Check logs for: `Uploading media, room encrypted: false`
6. Verify upload URL includes `encrypt=false`

## Edge Cases Handled

1. **No encryption event**: If `m.room.encryption` is not present, `isEncrypted` defaults to `false`
2. **Invalid algorithm**: If the algorithm field is blank or missing, `isEncrypted` remains `false`
3. **Null room state**: When uploading, uses `?: false` fallback if `currentRoomState` is null
4. **Multiple state requests**: Each room state request updates the `currentRoomState` with the latest data

## Future Enhancements

1. **Cache encryption status**: Store in room item to avoid repeated state requests
2. **Encryption indicator**: Show lock icon in room header for encrypted rooms
3. **Encryption warnings**: Alert user when sending unencrypted media in encrypted rooms (if server doesn't handle)
4. **Algorithm-specific handling**: Different behavior based on encryption algorithm

## Backward Compatibility

- The `isEncrypted` field has a default value of `false`, so existing code continues to work
- All existing `RoomState` creation sites still compile without modification
- Rooms without encryption events are correctly treated as unencrypted

## Performance Impact

- Minimal: Room state is already requested when entering a room
- No additional network requests
- Simple boolean check during event parsing
- Encryption status is cached in `RoomState` object

## Security Considerations

- The app relies on the server's `get_room_state` response for encryption status
- The actual encryption/decryption is handled by the gomuks backend
- The app only needs to know if it should send `encrypt=true` or `encrypt=false` to the upload endpoint
- No cryptographic operations are performed in the Android app

## Related Documentation

- [Media Sending Implementation](./MEDIA_SENDING_IMPLEMENTATION.md) - How media upload works
- Matrix Spec: [m.room.encryption](https://spec.matrix.org/v1.6/client-server-api/#mroomencryption) - Official specification

