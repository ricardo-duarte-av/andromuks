# Media Sending Implementation

## Overview
This document describes the implementation of media (image) sending functionality in Andromuks. Users can now select images from their device, add optional captions, and send them to Matrix rooms.

## Components Created

### 1. MediaUploadUtils.kt
**Location:** `app/src/main/java/net/vrkknn/andromuks/utils/MediaUploadUtils.kt`

This utility class handles:
- **Image Upload**: Uploads media files to the gomuks backend via the `/_gomuks/upload` endpoint
- **BlurHash Encoding**: Calculates BlurHash strings for images to show placeholder previews
- **Metadata Extraction**: Gets image dimensions, file size, and MIME type
- **Response Parsing**: Extracts the `mxc://` URL from the upload response

Key functions:
- `uploadMedia()`: Main upload function that returns a `MediaUploadResult` containing all necessary metadata
- `encodeBlurHash()`: Implements the BlurHash algorithm to generate placeholder hashes
- Helper functions for MIME type detection and filename extraction

### 2. MediaPickerDialog.kt
**Location:** `app/src/main/java/net/vrkknn/andromuks/utils/MediaPickerDialog.kt`

Contains two Composable components:
- **MediaPreviewDialog**: Shows a preview of the selected image with a caption input field
- **UploadingDialog**: Displays a loading indicator while the image is being uploaded

### 3. sendMediaMessage() in AppViewModel
**Location:** `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt` (lines 2028-2079)

Handles sending media messages through the WebSocket connection with the following structure:
```json
{
  "command": "send_message",
  "request_id": <number>,
  "data": {
    "room_id": "<room_id>",
    "base_content": {
      "msgtype": "m.image",
      "body": "<caption or filename>",
      "url": "<mxc:// URL>",
      "info": {
        "mimetype": "<image/jpeg, etc>",
        "xyz.amorgan.blurhash": "<blurhash>",
        "w": <width>,
        "h": <height>,
        "size": <file_size>
      },
      "filename": "<filename>"
    },
    "text": "",
    "mentions": {
      "user_ids": [],
      "room": false
    },
    "url_previews": []
  }
}
```

### 4. UI Integration in RoomTimelineScreen
**Location:** `app/src/main/java/net/vrkknn/andromuks/RoomTimelineScreen.kt`

Added:
- **Attach Button**: Icon button next to the message input field
- **Media Picker Launcher**: Uses Android's `ActivityResultContracts.GetContent()` to let users select images
- **State Management**: Tracks selected media URI, preview dialog visibility, and upload progress
- **Upload Flow**: Coordinates the upload and message sending process

## How It Works

### User Flow
1. User clicks the attach button (📎) next to the message input
2. System media picker opens, user selects an image
3. Preview dialog shows with the selected image and optional caption field
4. User can add a caption or leave it blank
5. User clicks "Send"
6. Upload dialog appears showing progress
7. Image is uploaded to the server, BlurHash is calculated
8. Message is sent via WebSocket with all metadata
9. Dialog closes and the image appears in the chat timeline

### Technical Flow
1. **Image Selection**: `rememberLauncherForActivityResult` with `GetContent` contract
2. **Preview**: Shows full image with Coil's `AsyncImage`
3. **Upload**: 
   - Reads image bytes from URI
   - Decodes bitmap to get dimensions
   - Calculates BlurHash (4x3 components by default)
   - POSTs to `/_gomuks/upload` with proper headers
   - Parses `mxc://` URL from response
4. **Send**: Constructs message with `base_content` containing all metadata
5. **Display**: Existing `MediaMessage` component handles rendering

## Upload Endpoint Details

**URL Format:**
```
{homeserverUrl}/_gomuks/upload?encrypt={bool}&progress=false&filename={encoded_filename}&resize_percent=100
```

**Parameters:**
- `encrypt`: Currently always `false` (TODO: detect encrypted rooms)
- `progress`: Set to `false` (not using progress streaming)
- `filename`: URL-encoded original filename
- `resize_percent`: Set to `100` (no resizing)

**Headers:**
- `Cookie: gomuks_auth={authToken}`
- `Content-Type: {image/jpeg, image/png, etc.}`

**Response:**
```json
{
  "mxc": "mxc://server/mediaId"
}
```

## BlurHash Implementation

The BlurHash encoder implements the algorithm from https://github.com/woltapp/blurhash

- **Components**: Uses 4x3 components (configurable)
- **Algorithm**: Converts image to frequency domain using cosine transforms
- **Output**: Base83-encoded string (e.g., `L570}IjEFtRlIWWYM|s:1Oax#ps.`)
- **Usage**: Provides instant placeholder while the actual image loads

## Encryption Detection (✅ Implemented)

### How It Works
The app now automatically detects room encryption status:

1. **RoomState Enhancement**: Added `isEncrypted` field to `RoomState` data class
2. **Detection via get_room_state**: When entering a room, the app sends `get_room_state` command
3. **Event Parsing**: Looks for `m.room.encryption` event in the response
4. **Algorithm Check**: If the event exists with an `algorithm` field (e.g., `m.megolm.v1.aes-sha2`), the room is marked as encrypted
5. **Upload Integration**: Media uploads use the actual encryption status from `currentRoomState`

### Implementation Details
- Modified `parseRoomStateFromEvents()` to check for `m.room.encryption` event
- Added encryption detection when the `get_room_state` response is received
- Updated media upload call to use `appViewModel.currentRoomState?.isEncrypted ?: false`
- Comprehensive logging to track encryption status detection

## Known Limitations & Future Work

### 1. Encryption Support ✅ COMPLETED
**Current State**: Encryption detection is fully implemented

The app now:
- ✅ Detects if a room is encrypted via `m.room.encryption` event
- ✅ Passes correct encryption status to upload endpoint
- ✅ Updates `RoomState` with encryption information
- ⚠️ Note: The gomuks backend handles the actual encryption/decryption

### 2. Video Support
**Current State**: Only images are supported

**TODO**:
- Add video MIME type support
- Handle video thumbnails
- Add `msgtype: "m.video"` support
- Consider video duration and codecs metadata

### 3. File Size Limits
**Current State**: No file size validation

**TODO**:
- Add maximum file size check before upload
- Show error message if file is too large
- Consider image compression for large files

### 4. Progress Feedback
**Current State**: Simple loading dialog with no progress percentage

**TODO**:
- If the server supports `progress=true`, parse progress updates
- Show actual upload percentage
- Add cancel button for long uploads

### 5. Error Handling
**Current State**: Errors are logged but not shown to user

**TODO**:
- Add error dialog for upload failures
- Show specific error messages (network, file too large, etc.)
- Add retry mechanism

### 6. Media Types
**Current State**: Only `image/*` MIME types

**TODO**:
- Add support for other media types (documents, audio)
- Different handling for different file types
- Preview for non-image files

## Testing Checklist

### Basic Functionality
- [x] Attach button appears in message input area
- [ ] Media picker opens when attach button is clicked
- [ ] Selected image appears in preview dialog
- [ ] Caption can be added to image
- [ ] Upload dialog shows during upload
- [ ] Image uploads successfully to server
- [ ] BlurHash is calculated correctly
- [ ] Media message appears in timeline
- [ ] Caption displays correctly (when provided)
- [ ] Filename displays correctly (when no caption)

### Encryption Detection
- [x] Room state is requested when entering a room
- [x] m.room.encryption event is detected when present
- [x] isEncrypted flag is set correctly in RoomState
- [ ] Upload endpoint receives correct encrypt parameter (true for encrypted rooms)
- [ ] Upload endpoint receives correct encrypt parameter (false for unencrypted rooms)
- [ ] Media displays correctly in encrypted rooms
- [ ] Media displays correctly in unencrypted rooms

### Error Handling
- [ ] Handles upload failures gracefully
- [ ] Large images are handled appropriately
- [ ] Shows error message on network failure

## Dependencies

All required dependencies were already present in the project:
- **OkHttp**: For HTTP upload requests
- **Coil**: For image loading and display
- **Compose UI**: For dialogs and UI components
- **Coroutines**: For async operations

No additional dependencies were added.

## Files Modified

1. `app/src/main/java/net/vrkknn/andromuks/AppViewModel.kt`
   - Added `sendMediaMessage()` function
   - Modified `parseRoomStateFromEvents()` to detect encryption
   - Updated `requestRoomTimeline()` to request room state

2. `app/src/main/java/net/vrkknn/andromuks/RoomTimelineScreen.kt`
   - Added attach button
   - Added media picker launcher
   - Added state management for media selection
   - Added dialogs for preview and upload progress
   - Updated media upload to use actual room encryption status

3. `app/src/main/java/net/vrkknn/andromuks/RoomItem.kt`
   - Added `isEncrypted` field to `RoomState` data class

## Files Created

1. `app/src/main/java/net/vrkknn/andromuks/utils/MediaUploadUtils.kt`
   - Upload functionality
   - BlurHash encoding
   - Metadata extraction

2. `app/src/main/java/net/vrkknn/andromuks/utils/MediaPickerDialog.kt`
   - Preview dialog component
   - Upload progress dialog component

## Permissions

No new permissions required. The implementation uses:
- `INTERNET` permission (already present) for uploads
- System media picker (no READ_EXTERNAL_STORAGE needed on modern Android)

## Usage Example

From the user's perspective:
1. Open any room
2. Click the attach button (📎) to the left of the message input
3. Select an image from your gallery
4. (Optional) Add a caption
5. Click "Send"
6. The image uploads and appears in the chat

## Architecture Decisions

### Why BlurHash?
- Provides instant visual feedback while image loads
- Small string size (typically 20-30 characters)
- Already used throughout the app for existing media
- Standard in Matrix ecosystem

### Why Separate Upload and Send?
- Upload can be slow, especially on mobile networks
- Allows for better error handling at each stage
- Matches the server's expected workflow
- Enables future progress tracking

### Why Activity Result API?
- Modern Android best practice
- Handles permissions automatically
- Better than deprecated `onActivityResult`
- Scoped to the composable lifecycle

## Code Quality

- No linter errors
- Follows existing code patterns in the project
- Comprehensive logging for debugging
- Proper error handling with try-catch blocks
- Clean separation of concerns (upload, UI, messaging)

