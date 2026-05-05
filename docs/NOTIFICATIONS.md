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

### Notification body formatting

FCM push payloads carry only the plain-text `body` field — the `sanitized_html` / `htmlBody` field is not present. `showEnhancedNotification` therefore cannot call `htmlToNotificationText`. Instead it calls `formatNotificationBody(text)`, a private single-pass parser defined in `EnhancedNotificationDisplay`, which returns a `SpannableStringBuilder` with Android text spans applied:

| Markdown syntax | Span applied |
|---|---|
| `` `code` `` | `TypefaceSpan("monospace")` |
| `**bold**` | `StyleSpan(BOLD)` |
| `*italic*` | `StyleSpan(ITALIC)` |

The parser walks the string left-to-right, checking in the order above (code → bold → italic) so that `**` is consumed before a single `*` is considered. Unmatched delimiters (no closing counterpart found) are emitted verbatim. Underscore-italic (`_..._`) is intentionally omitted to avoid false positives inside Matrix user IDs and URLs.

The resulting `CharSequence` is passed directly as the `MessagingStyle.Message` text, which Android renders with the spans intact in the notification shade on API 24+.

### Image notifications — two-phase approach

Image messages (notifications where the FCM payload contains an `image` field) use a deferred download pattern to avoid the same Doze/network restriction that affects avatar loading:

**Phase 1 — FCM callback (`showEnhancedNotification`):** The notification is posted immediately with the message text body (e.g. a caption or "📷 Photo"). No image download is attempted. The image URL and MXC cache key are parsed and passed to `NotificationImageWorker.enqueue()`.

**Phase 2 — `NotificationImageWorker`:** A `CoroutineWorker` marked as **expedited** (`setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`) and constrained to `NetworkType.CONNECTED`. Expedited work is scheduled by `JobScheduler` at the next available slot rather than waiting for a Doze maintenance window — this is the critical difference from a plain constrained job, which can be deferred for minutes or hours on aggressive OEMs (Samsung One UI, MIUI, etc.). If the device has exhausted its expedited quota the job falls back to a normal-priority scheduled job. When it runs:

1. Checks if the notification is still active (bail if dismissed).
2. Checks `IntelligentMediaCache` disk cache; downloads via `IntelligentMediaCache.downloadAndCache` only on a miss.
3. Wraps the file in a `FileProvider` `content://` URI.
4. Extracts the existing `MessagingStyle` from the active notification.
5. Rebuilds the style — replaces the last message with a new `Message.setData(mimeType, imageUri)` version.
6. Re-posts with the same notification ID and `setSilent(true)` (no sound/vibration on update).

The worker uses `ExistingWorkPolicy.KEEP` keyed by `"notif_image_$roomId"`. If a download is already running for that room it is not cancelled; a second concurrent image for the same room is rare and cancelling an in-flight download is worse than missing the update for the second. Retries up to 3 times on transient failure, with exponential backoff starting at 15 s.

**Auth token:** `doWork()` re-reads the auth token from SharedPreferences (`AndromuksAppPrefs / image_auth_token`, falling back to `gomuks_auth_token`) at run time rather than relying on the value captured at enqueue time. This ensures a delayed job — scheduled by Doze and run minutes later — uses a current credential. The token is used as a `Cookie: gomuks_auth=<token>` header by `IntelligentMediaCache.downloadAndCache`; however, the image URL passed to the worker already contains `?image_auth=<batchToken>` (appended in `FCMService.handleMessageNotification`) which is the primary credential. The cookie is a fallback for cases where the URL was built without a batch token.

### Dismiss handling

`handleDismissNotification` cancels active notifications for a room when the backend signals the conversation was read. It will **not** cancel if:
- The room is within a 5-second **reply protection window** — set by `FCMService.markRoomReplied(roomId)` when the user replies via the inline notification action (`NotificationReplyReceiver`). Replying marks the room read, which causes the backend to immediately push a dismiss FCM; this window prevents that auto-dismiss from collapsing the notification. Dismisses arriving after the window expire are treated as true remote dismissals and proceed normally.
- A chat bubble for that room is actively open (`BubbleTracker.isBubbleOpen`).

The protection window is **not** set for replies sent from other clients (Webmuks, another Andromuks instance, etc.). Those replies arrive via WebSocket sync and do not go through `NotificationReplyReceiver`, so the dismiss FCM they trigger is allowed through and correctly clears the Android notification.

## Bubble integration

Chat bubbles (Android 11+) are tracked via the `BubbleTracker` singleton. `FCMService` consults it before dismissing notifications to avoid collapsing an open bubble.

Auto-expanded bubbles are tracked in `EnhancedNotificationDisplay.autoExpandedBubbleRooms` to avoid re-expanding on every notification update.

## Notification Tap Navigation

Tapping a notification PendingIntent delivers the intent to `MainActivity` via `onCreate` (cold start) or `onNewIntent` (warm start). Both paths call:

```
appViewModel.setDirectRoomNavigation(roomId, notificationTimestamp, targetEventId)
```

This sets `directRoomNavigation`, increments `directRoomNavigationTrigger`, and clears `pendingRoomToRestore`.

### Critical invariant: `onNewIntent` must NOT call `navigateToRoomWithCache`

`onNewIntent` (warm start) must **only** call `setDirectRoomNavigation`. It must never call `navigateToRoomWithCache` directly.

**Why:** `navigateToRoomWithCache` synchronously calls `updateCurrentRoomIdInPrefs()` on the calling thread, which sets `currentRoomId = newRoom` and (when `previousRoomId != newRoom`) clears `timelineEvents = emptyList()` immediately — before any UI handler has reacted. The currently-visible `RoomTimelineScreen` observes `currentRoomId` changing away from its own `roomId` and `timelineEvents` suddenly empty; its `LaunchedEffect(roomId)` guard for `isAlreadyLoaded` fires `navigateToRoomWithCache(oldRoom)`, racing with the notification navigation. This produces either a navigation that stops mid-way or a room that opens with an empty, non-loading timeline despite a cache being available.

`setDirectRoomNavigation` only increments `directRoomNavigationTrigger` and records the target room — it does not touch `currentRoomId` or `timelineEvents`. The authorised callers of `navigateToRoomWithCache` for notification-triggered flows are:
- `RoomListScreen.LaunchedEffect(navigationTrigger)` — warm-start, different room
- `RoomTimelineScreen.LaunchedEffect(navTrigger)` — different room handler
- `AuthCheck.kt` navigation callback — cold-start path (called from a coroutine, not synchronously on the main thread at risk of racing UI)

### Same-room notification guard in `RoomListScreen`

`RoomListScreen.LaunchedEffect(navigationTrigger)` fires whenever `directRoomNavigationTrigger` increments — including when the user is already on `RoomTimelineScreen` for the target room (user had the room open, backgrounded, tapped its notification). When that happens, both `RoomListScreen.LaunchedEffect(navigationTrigger)` and `RoomTimelineScreen.LaunchedEffect(navTrigger)` fire simultaneously. `RoomTimelineScreen`'s same-room handler correctly clears `directRoomNavigation` and sets the scroll-to-event highlight without reloading the timeline.

To prevent a race where `RoomListScreen` wins and incorrectly calls `navigateToRoomWithCache` (causing an unnecessary reload) and then `navigateToRoomTimelineForExternalEntry` (which would push a duplicate back-stack entry because `auth_check` is already gone from the stack and `popUpTo` is a no-op), `RoomListScreen.LaunchedEffect(navigationTrigger)` contains a same-room guard:

```kotlin
if (directRoomId == appViewModel.currentRoomId) {
    // same room already open — RoomTimelineScreen's same-room handler will consume this
    return@LaunchedEffect
}
```

This guard is placed **before** `clearDirectRoomNavigation()` so the token is still available for `RoomTimelineScreen`.

### `navigateToRoomTimelineForExternalEntry` uses `launchSingleTop`

`navigateToRoomTimelineForExternalEntry` (defined in `RoomListScreen.kt`) uses `launchSingleTop = true` as defence-in-depth:

```kotlin
private fun NavController.navigateToRoomTimelineForExternalEntry(roomId: String) {
    navigate("room_timeline/$roomId") {
        popUpTo("auth_check") { inclusive = true }
        launchSingleTop = true
    }
}
```

`popUpTo("auth_check")` clears the back-stack up to `auth_check` (the root). On warm start, `auth_check` is no longer in the back-stack so `popUpTo` is a no-op. `launchSingleTop = true` prevents a duplicate `room_timeline/<id>` entry from being pushed if the destination is already at the top of the back-stack (e.g. if the same-room guard above incorrectly fell through or another handler raced).

### `openedViaDirectNotification` invariant

Every code path that navigates to `room_timeline/<id>` as a result of a notification or shortcut tap **must** set `appViewModel.openedViaDirectNotification = true` immediately before calling `navController.navigateToRoomTimelineForExternalEntry(roomId)`.

This flag is checked inside `navigateToRoomListIfNeeded`. When `true`, the force-navigation back to `room_list` (which fires when the startup navigation callback runs after `directRoomNavigation` has already been consumed) is suppressed.

Affected call sites (all must set the flag):
- `RoomListScreen.kt` — every `navigateToRoomTimelineForExternalEntry` call inside LaunchedEffects for `directRoomId`/`pendingRoomId` paths (cached and non-cached), the spacesLoaded observer, the timeout fallback, and the `navigationTrigger` reactive handler.
- `RoomTimelineScreen.kt` — the `LaunchedEffect(navTrigger)` "different room" handler.

Missing the flag at any site re-introduces the redirect-back-to-room_list race (Bug 7 in AUTHCHECK.md).

### `isTimelineLoading` reset in abort paths

`navigateToRoomWithCache` sets `isTimelineLoading = true` synchronously before launching its coroutine. The coroutine contains two early-return abort checks (after the batch flush, and before `requestRoomTimeline`) that trigger when a concurrent navigation supersedes the current one (`currentRoomId != roomId`). Both abort handlers reset `isTimelineLoading = false` when `currentRoomId.isEmpty()` (user navigated back to room list), preventing the loading spinner from sticking permanently.

## Shortcut / conversation API

Each room notification creates or updates a `ShortcutInfoCompat` (via `ConversationsApi`) so Android associates the notification with a conversation shortcut. This is required for `MessagingStyle` and bubble support.

`ConversationsApi.onRoomActivity(roomItem)` is called inside the `synchronized` block in `showEnhancedNotification`, **after** `notificationManager.notify()`. This ensures only notifications that are actually posted push the room to the top of the Direct Share ranking — silent/suppressed notifications (low-priority rooms, same-room-app-visible suppression, bubble-already-visible silent updates) exit via early `return` before reaching the synchronized block and do not affect the ranking.
