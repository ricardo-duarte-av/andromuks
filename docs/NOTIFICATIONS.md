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

Media endpoints (`/_gomuks/media/...`) require a gomuks HMAC token for authentication. The session cookie (`gomuks_auth_token`) is only valid when an active OkHttp session exists — it is rejected in the `NotificationImageWorker` context where no such session is maintained. The server accepts the token as either a `?image_auth=<token>` query parameter or an `Authorization: Image <token>` header.

**Push payload token (used for notifications):** The gomuks backend embeds an `image_auth` field at the top level of every push payload batch that contains messages. This is a 24-hour HMAC token generated once per batch (`generateImageToken(24*time.Hour)` in `push.go`). `FCMService.handleMessageNotification` reads this token (`batchImageAuth`) and uses it for all media downloads in that notification:

- **Message image URL** — appended as `?image_auth=<token>` before storing in `NotificationData.image`. By the time `NotificationImageWorker` downloads the file, the credential is already in the URL.
- **Avatar URLs** — the token is stored in `NotificationData.imageAuthToken` and threaded to `EnhancedNotificationDisplay.loadAvatarBitmap`, which appends `?image_auth=<token>` to the HTTP download URL for `/_gomuks/media/` endpoints. The persistent session token (`image_auth_token` from SharedPrefs) is used as a fallback when no batch token is available (e.g. for the current user's avatar).

**Avatar URL normalisation:** Push payloads can send avatar URLs in three forms (`mxc://server/mediaId`, `_gomuks/media/server/mediaId[?params]`, or a full `https://host/_gomuks/media/server/mediaId[?params]` URL). `FCMService.normalizeToMxcUrl()` strips query params and converts any form to the canonical `mxc://server/mediaId` before it is stored in `NotificationData`. This is the same key used by `IntelligentMediaCache`, so any avatar previously downloaded by `ConversationsApi` (shortcuts) or `PersonsApi` (contacts sync) will be a cache hit.

**WebSocket token (used by the main app):** The backend also issues an `image_auth_token` via a WebSocket command after connecting. `AppViewModel.updateImageAuthToken` persists it to `AndromuksAppPrefs / image_auth_token`. This is a separate mechanism used by the running app for in-session media requests; it is **not** involved in notification image downloads.

## EnhancedNotificationDisplay

Responsible for building and posting the actual `Notification` via `NotificationManagerCompat`. Uses `MessagingStyle` for conversation-style notifications with per-sender avatars.

### Notification channels

| Channel ID | Name | Used for |
|---|---|---|
| `matrix_direct_messages` | Direct Messages | DM rooms |
| `matrix_group_messages` | Group Messages | Group rooms |

### Avatar loading

Avatars (room, sender, current user) are downloaded synchronously in the FCM callback before the notification is posted. The load order is:

1. Check `IntelligentMediaCache` (disk cache, keyed by `mxc://server/mediaId`) — no network required.
2. If not cached — attempt download via `IntelligentMediaCache.downloadAndCache` using the auth-bearing HTTP URL (`/_gomuks/media/...?image_auth=<token>`). This may fail silently in Doze; in that case step 3 applies.
3. On any failure — use a generated letter-mark fallback avatar. The notification still posts immediately.

Cache hits come from `ConversationsApi` (shortcut icon downloads) and `PersonsApi` (contacts sync). `AvatarImage.kt` does **not** back-fill `IntelligentMediaCache` — UI avatar loads go through Coil only. Avatars for contacts whose shortcuts have never been built will fall back to a letter-mark on first delivery and be correct on subsequent notifications once a shortcut or contacts sync has populated the cache.

Note: there is **no** `PowerManager.isDeviceIdleMode` guard in the current implementation — unlike image downloads (which are fully deferred to `NotificationImageWorker`), avatar downloads run in the FCM callback and rely on the cache being warm.

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

**Auth token:** The image URL passed to the worker already contains `?image_auth=<token>` (appended in `FCMService.handleMessageNotification` from the push payload's top-level `image_auth` field). No separate credential lookup is needed at download time.

### Dismiss handling

`handleDismissNotification` cancels active notifications for a room when the backend signals the conversation was read. It will **not** cancel if:
- The last message in the notification was sent by the current user (prevents self-dismiss after replying).
- A chat bubble for that room is actively open (`BubbleTracker.isBubbleOpen`).

## Bubble integration

Chat bubbles (Android 11+) are tracked via the `BubbleTracker` singleton. `FCMService` consults it before dismissing notifications to avoid collapsing an open bubble.

Auto-expanded bubbles are tracked in `EnhancedNotificationDisplay.autoExpandedBubbleRooms` to avoid re-expanding on every notification update.

## Shortcut / conversation API

Each room notification creates or updates a `ShortcutInfoCompat` (via `ConversationsApi`) so Android associates the notification with a conversation shortcut. This is required for `MessagingStyle` and bubble support.
