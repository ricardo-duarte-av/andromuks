# Push Notifications Architecture

## Overview

Push notifications flow through three layers:

```
Gomuks backend
    ↓  HTTP POST /_gomuks/push/fcm  (JSON: token, owner, payload, high_priority)
gomuks/push gateway  (push.go)
    ↓  Firebase Admin SDK
FCM
    ↓  onMessageReceived()
FCMService.kt
    ↓
EnhancedNotificationDisplay.kt  →  NotificationManagerCompat
```

## FCM Priority

The push gateway (`gomuks/push`) passes `high_priority` through verbatim from the gomuks backend request. The backend sets it based on `notif.HasImportant`, which is `true` when at least one message in the batch has `Sound: true` (determined by the user's Matrix push rules).

- **High priority** — FCM wakes the device immediately, bypassing Doze. Used for mentions, DMs, and any room whose push rule includes sound.
- **Normal priority** — deferred by Android Doze; delivered in batch on next maintenance window. Used for silent notifications (e.g. dismiss/read-receipt payloads).

If notifications for a room are only arriving when the screen is turned on, check that the room's Matrix push rule includes `"sound": "default"`.

## FCMService

`FCMService` extends `FirebaseMessagingService`. Key responsibilities:

- Decrypts the encrypted `payload` field using the stored push encryption key (`web_client_prefs / push_encryption_key`, base64-encoded AES key).
- Routes decrypted JSON to `handleMessageNotification` or `handleDismissNotification` based on whether the payload has a `messages` or `dismiss` key.
- Suppresses notifications for the currently open room when the app is in the foreground (`shouldSuppressNotification`).
- Skips notifications for rooms in `low_priority_rooms` (SharedPreferences set).
- Verifies `self.id` against `current_user_id` to discard notifications for other accounts.
- Uses `pendingNotifications` (synchronized set) to handle the race condition where a dismiss arrives before the notification is posted.

### Image auth token

The gomuks backend issues a short-lived JWT image token (`image_auth_token` WebSocket command) after the WebSocket connects. This token is required for downloading `?encrypted=true` media; the WebSocket session token (`gomuks_auth_token`) is **not** accepted for encrypted content.

**`AppViewModel.updateImageAuthToken`** persists the token to `AndromuksAppPrefs / image_auth_token` on every receipt (the backend refreshes it periodically).

**`FCMService.onCreate`** reads `image_auth_token` from SharedPreferences and passes it to `EnhancedNotificationDisplay` as the download credential, falling back to `gomuks_auth_token` only if no image token has been stored yet (first launch, before the first WebSocket session). Without this, image notifications always fall back to the text body ("Sent an image") in the release build.

## EnhancedNotificationDisplay

Responsible for building and posting the actual `Notification` via `NotificationManagerCompat`. Uses `MessagingStyle` for conversation-style notifications with per-sender avatars.

### Notification channels

| Channel ID | Name | Used for |
|---|---|---|
| `matrix_direct_messages` | Direct Messages | DM rooms |
| `matrix_group_messages` | Group Messages | Group rooms |

### Avatar loading

Avatars (room, sender, current user) are loaded before the notification is posted. The load order is:

1. Check `IntelligentMediaCache` (disk cache) — no network required.
2. If not cached and device **is in Doze** (`PowerManager.isDeviceIdleMode`) — return `null`, use fallback avatar immediately.
3. If not cached and device is awake — download via `IntelligentMediaCache.downloadAndCache` and cache for next time.

**Critical invariant:** Never block notification posting on a network download during Doze. Doze restricts network access even after a high-priority FCM wake-up; blocking here causes all notifications to batch and fire when the screen turns on. The Doze guard in `loadAvatarBitmap` and the inline current-user avatar block enforces this.

Fallback avatars (generated lettermarks from room/sender name) are used whenever the real avatar is unavailable.

### Image notifications — two-phase approach

Image messages (notifications where the FCM payload contains an `image` field) use a deferred download pattern to avoid the same Doze/network restriction that affects avatar loading:

**Phase 1 — FCM callback (`showEnhancedNotification`):** The notification is posted immediately with the message text body (e.g. a caption or "📷 Photo"). No image download is attempted. The image URL and MXC cache key are parsed and passed to `NotificationImageWorker.enqueue()`.

**Phase 2 — `NotificationImageWorker`:** A `CoroutineWorker` constrained to `NetworkType.CONNECTED`. WorkManager schedules it via `JobScheduler`, which grants a network-accessible execution window outside Doze. When it runs:

1. Checks if the notification is still active (bail if dismissed).
2. Checks `IntelligentMediaCache` disk cache; downloads via `IntelligentMediaCache.downloadAndCache` only on a miss.
3. Wraps the file in a `FileProvider` `content://` URI.
4. Extracts the existing `MessagingStyle` from the active notification.
5. Rebuilds the style — replaces the last message with a new `Message.setData(mimeType, imageUri)` version.
6. Re-posts with the same notification ID and `setSilent(true)` (no sound/vibration on update).

The worker uses `ExistingWorkPolicy.REPLACE` keyed by `"notif_image_$roomId"` so that rapid back-to-back image messages for the same room don't pile up. It retries up to twice on transient download failure.

**Auth token:** The worker receives the `image_auth_token` (JWT) from `EnhancedNotificationDisplay` at enqueue time, not from SharedPreferences, so the token is always the freshest value available at the moment the FCM message was received.

### Dismiss handling

`handleDismissNotification` cancels active notifications for a room when the backend signals the conversation was read. It will **not** cancel if:
- The last message in the notification was sent by the current user (prevents self-dismiss after replying).
- A chat bubble for that room is actively open (`BubbleTracker.isBubbleOpen`).

## Bubble integration

Chat bubbles (Android 11+) are tracked via the `BubbleTracker` singleton. `FCMService` consults it before dismissing notifications to avoid collapsing an open bubble.

Auto-expanded bubbles are tracked in `EnhancedNotificationDisplay.autoExpandedBubbleRooms` to avoid re-expanding on every notification update.

## Shortcut / conversation API

Each room notification creates or updates a `ShortcutInfoCompat` (via `ConversationsApi`) so Android associates the notification with a conversation shortcut. This is required for `MessagingStyle` and bubble support.
