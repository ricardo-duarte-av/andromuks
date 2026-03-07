# Matrix Contacts Integration

This document explains how to integrate Matrix users with Android's Contacts API, enabling Matrix users to appear in the system contacts app and allowing other apps to offer "Send Matrix message" actions (similar to WhatsApp).

## Overview

The Contacts API integration allows you to:
- Sync Matrix users to Android contacts
- See Matrix users in the system contacts app
- Enable "Send Matrix message" actions from other apps
- Have Matrix contacts appear in contact pickers

## Architecture

### Components

1. **ContactsSyncService** (`ContactsSyncService.kt`)
   - Handles syncing Matrix users to Android contacts
   - Manages contact creation, updates, and deletion
   - Handles avatar syncing

2. **ContactsIntegrationExample** (`ContactsIntegrationExample.kt`)
   - Example integration with AppViewModel
   - Shows how to sync contacts from DMs, rooms, or all known users

3. **Manifest Updates**
   - Added `READ_CONTACTS` and `WRITE_CONTACTS` permissions
   - Added intent filters for handling "Send Matrix message" actions

## How It Works

### Contact Creation & Matching

When a Matrix user is synced to contacts:

**If phone/email provided:**
1. Checks if an existing contact matches by phone number or email
2. If match found: **Merges** Matrix data into existing contact (no duplicate!)
3. If no match: Creates new contact with phone/email included
4. Android will automatically aggregate contacts with matching phone/email

**If no phone/email:**
1. Creates a new Matrix contact
2. User can manually merge later via "Add to existing contact" in Android Contacts app

**Contact data added:**
- Display name from Matrix profile
- Matrix user ID as custom MIME type
- Phone number (if provided) - enables automatic matching
- Email (if provided) - enables automatic matching
- Avatar (if available)
- `matrix:u/` URI for intent handling

### Automatic Contact Matching

**Example scenario:**
- Existing contact: "Thalita Santos" with phone +351919100753 and email thalitasantos_877@gmail.com
- Matrix user: @thalitasantos:aguiarvieira.pt with phone +351919100753

**Result:** Matrix contact is automatically merged with existing contact! Android recognizes the matching phone number and combines them into one contact entry.

**If phone/email not available:**
- Matrix contact is created separately
- User can manually merge via Android Contacts app: "Add to existing contact"

### Intent Handling

When someone clicks "Send Matrix message" from a contact:
1. Android sends an intent with `matrix:u/` URI
2. MainActivity's existing `extractRoomIdFromMatrixUri()` handles it
3. The app resolves the user ID to a direct message room
4. Opens the conversation

## Usage

### Basic Integration

```kotlin
// In your AppViewModel or similar
val contactsIntegration = ContactsIntegrationExample(
    context = context,
    appViewModel = this,
    homeserverUrl = homeserverUrl
)

// Sync contacts from direct messages
contactsIntegration.syncDirectMessageContacts()

// Sync contacts from a specific room
contactsIntegration.syncRoomMemberContacts(roomId)

// Sync all known users
contactsIntegration.syncAllKnownUsers()
```

### Providing Phone/Email for Automatic Matching

To enable automatic contact matching, you need to provide phone numbers and/or emails when creating MatrixUser objects:

```kotlin
// If you have access to phone/email from Matrix account data or 3PIDs:
val matrixUser = MatrixUser(
    userId = "@thalitasantos:aguiarvieira.pt",
    displayName = "Thalita Santos",
    avatarUrl = "...",
    phoneNumber = "+351919100753",  // Enables automatic matching!
    email = "thalitasantos_877@gmail.com"  // Also enables matching!
)

contactsSyncService.syncContacts(listOf(matrixUser))
```

**Note:** Matrix profiles don't directly expose phone/email. You may need to:
- Extract from Matrix account data (3PIDs)
- Get from user's profile if they've shared it
- Allow users to manually link contacts if not available

### When to Sync

**Recommended sync triggers:**
- After receiving a direct message from a new user
- Periodically (e.g., every sync_complete) for DM contacts
- When user profile is updated (display name, avatar)
- On app startup (optional, for full sync)

**Example integration in AppViewModel:**

```kotlin
// After processing sync_complete
fun handleSyncComplete(data: JSONObject) {
    // ... existing sync handling ...
    
    // Sync contacts for new DM users
    if (syncRooms.any { it.isDirectMessage }) {
        CoroutineScope(Dispatchers.IO).launch {
            val contactsIntegration = ContactsIntegrationExample(
                context = appContext!!,
                appViewModel = this@AppViewModel,
                homeserverUrl = homeserverUrl
            )
            contactsIntegration.syncDirectMessageContacts()
        }
    }
}
```

### Performance Considerations

- **Avatar syncing**: Can be disabled for faster syncs (`syncAvatars = false`)
- **Batch operations**: ContactsSyncService uses batch operations for efficiency
- **Incremental updates**: Only updates contacts that changed
- **Rate limiting**: Consider debouncing sync operations

### Permissions

The app requires:
- `READ_CONTACTS` - To check existing contacts
- `WRITE_CONTACTS` - To create/update contacts
- `GET_ACCOUNTS` - To create sync account

These permissions are automatically requested by Android when needed.

## Custom MIME Type

The integration uses a custom MIME type:
```
vnd.android.cursor.item/vnd.net.vrkknn.andromuks.matrix.user
```

This allows Android to:
- Recognize Matrix contacts
- Show "Send Matrix message" in contact actions
- Group Matrix contacts together

## Intent Filters

The manifest includes intent filters to handle:
- `matrix:u/` URIs (already handled by MainActivity)
- Custom MIME type intents from contacts

## Limitations

1. **Contact limits**: Android may limit the number of contacts per account
2. **Avatar size**: Large avatars may impact performance
3. **Sync frequency**: Too frequent syncing may drain battery
4. **Account management**: Contacts are tied to a sync account
5. **Phone/Email availability**: Matrix profiles don't directly expose phone/email, so automatic matching requires:
   - Access to Matrix account data (3PIDs)
   - User sharing phone/email in profile
   - Manual linking by user if not available

## Contact Matching Behavior

### Automatic Matching (Recommended)
- **Requires:** Phone number OR email address
- **How it works:** Android automatically matches contacts with identical phone/email
- **Result:** Matrix contact is merged with existing contact (no duplicate)

### Manual Matching
- **When:** Phone/email not available
- **How it works:** User manually merges via Android Contacts app
- **Steps:** Open contact → "Add to existing contact" → Select existing contact

### Best Practice
- Try to extract phone/email from Matrix account data (3PIDs) if available
- If not available, contacts will be separate but user can merge manually
- Android's contact aggregation is smart - it will suggest matches based on name similarity too

## Troubleshooting

### Contacts not appearing
- Check permissions are granted
- Verify sync account was created
- Check logs for errors

### "Send Matrix message" not showing
- Ensure intent filters are in manifest
- Verify custom MIME type is set correctly
- Check that `matrix:u/` URI format is correct

### Avatars not syncing
- Check IntelligentMediaCache has the avatar
- Verify file permissions
- Check bitmap conversion errors in logs

## Future Enhancements

Potential improvements:
- Sync contacts from all rooms (not just DMs)
- Add contact groups (e.g., "Matrix Contacts")
- Support contact photos from avatars
- Add contact notes (e.g., mutual rooms)
- Sync contact updates bidirectionally

## Security & Privacy

- Contacts are stored locally on device
- Matrix user IDs are stored in contacts (public data)
- Avatars are cached locally
- No data is sent to external services

## References

- [Android Contacts API](https://developer.android.com/guide/topics/providers/contacts-provider)
- [Custom MIME Types](https://developer.android.com/guide/topics/providers/contacts-provider#CustomMimeTypes)
- [Intent Filters](https://developer.android.com/guide/components/intents-filters)

