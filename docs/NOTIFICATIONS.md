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

### Avatar loading — cache-only in Phase 1, deferred to a worker on a miss

Avatars (room, sender, current user) are loaded **cache-only** in the FCM callback. `showEnhancedNotification` calls `loadAvatarBitmap`/`loadAvatarAsIcon`/`loadAvatarAsUriIcon` with `allowNetwork = false`:

1. Check `IntelligentMediaCache` (disk cache, keyed by `mxc://server/mediaId`) — no network. On a hit, the real avatar is used synchronously (instant).
2. On a **miss**, the loader returns `null`; the call site renders a generated letter-mark immediately and records the missed MXC URL (`deferRoomAvatar` / `deferSenderAvatar` / `deferMeAvatar`).
3. The notification is posted **right away** with whatever mix of real/lettermark avatars it has — the FCM callback never blocks on the network.

If any avatar was a miss (and/or the message has an image), after the post `showEnhancedNotification` enqueues a single `NotificationImageWorker` (Phase 2) carrying every deferred item.

**Why this changed:** avatars used to be downloaded *synchronously in the FCM callback before posting*. The callback only has a ~20 s `PARTIAL_WAKE_LOCK` budget, but `IntelligentMediaCache.downloadClient` has a 60 s `callTimeout`. In Doze — and especially in **battery-saver / "Sidecar" mode**, where the WebSocket and foreground service are torn down so FCM is the *only* delivery path — the radio is parked and a cold-cache avatar fetch could outlast the wake budget. The coroutine froze mid-download and the notification did not post until the device was next woken (the "notifications only arrive when I turn the screen on, even though FCM was delivered high-priority" symptom). The fix moves the network off the FCM thread entirely.

Cache hits come from `ConversationsApi` (shortcut icon downloads) and `PersonsApi` (contacts sync). `AvatarImage.kt` does **not** back-fill `IntelligentMediaCache` — UI avatar loads go through Coil only.

Fallback avatars (generated lettermarks from room/sender name) are pure-CPU (`Canvas` draw, no I/O) and are used whenever the real avatar is not yet cached.

The inline-reply update path (`updateNotificationWithReply`) is also cache-only by design and never downloads.

### NotificationImageWorker — the single Phase-2 media worker (image + avatars)

**One** worker finishes the notification, handling the message image *and* the missed avatars in a single pass. This is deliberate: avatars and media are both `mxc://`-keyed (they share one download primitive, `IntelligentMediaCache`), and — critically — one worker doing **one read of the active notification, one rebuild, one `notify()`** makes a clobbering re-post race structurally impossible. Two independent workers (an earlier image/avatar split) could read the same pre-update notification concurrently, and the second `notify()` would wipe the first's contribution (image *or* avatars lost). One writer, no race.

Expedited, `NetworkType.CONNECTED`, `ExistingWorkPolicy.KEEP` keyed `notif_media_<roomId>_<eventId>`. When it runs:

1. Bails if the notification is no longer active (dismissed / marked read).
2. Downloads everything deferred — the image and the room / sender / me avatars — **in parallel**, cache-first (re-reads `homeserver_url` and a fresh `image_auth_token` from SharedPreferences at run time). The image URL already carries `?encrypted=…&image_auth=…` from Phase 1; avatars rebuild their HTTP URL from the `mxc://` + `image_auth`. If nothing downloads it retries up to 3× with backoff.
3. Extracts the live `MessagingStyle` and rebuilds it in one pass: patches the **"me" Person** icon (root), every message `Person` from this sender, and `setData(image)` on the timestamp-matched **target message** (a message can be both — its `Person` is patched *and* its image set). Re-sets the **large icon** (room avatar for groups, sender for DMs; restored from the existing notification if not freshly downloaded).
4. Preserves channel, `contentIntent`, reply / mark-read actions, `event_id` extra, `when`, group/shortcut, plus `LocusId` and bubble metadata.
5. Re-posts with the same notification ID and `setSilent(true)` (visual-only, no re-alert), then refreshes the group summary.
6. Updates the in-memory cache so a later `showEnhancedNotification` rebuild keeps the result: `upgradeMessageToImage` for the image, `upgradeAvatarsInCache(roomId, senderId, senderIcon)` for the sender avatar. The "me" Person and large icon are not cached per-message — they self-correct on the next rebuild via the now-warm disk cache.
7. Returns `retry` if an image was requested but couldn't be fetched (so it lands on a later attempt), even when avatars already posted; avatar-only misses are not retried.

### MessagingStyle message history cache

`EnhancedNotificationDisplay.roomMessageCache` (companion object) is a per-room
`ConcurrentHashMap<String, ArrayDeque<MessagingStyle.Message>>` capped at
`MAX_CACHED_MESSAGES_PER_ROOM = 5`. Every incoming message is appended with
`addLast` then trimmed FIFO. On every `notify()` call the cached messages are
re-added to a freshly-built `MessagingStyle`, so the shade renders up to the
last 5 messages for the conversation.

**Why this is needed:** Android's `MessagingStyle` does not preserve history
across `notify()` calls. If we built a `MessagingStyle` containing only the
latest message, the shade would show only that one. The cache is the application
side of the contract.

**Lifetime:** in-memory, tied to process lifetime. No SharedPreferences backing.
A true cold start posts a notification with only the new message until 5 more
arrive.

**Clear sites — the cache must be cleared whenever the user (or the backend
on behalf of the user) acknowledges the conversation, so the next notification
does not replay messages the user has already seen:**

| Where | Why |
|---|---|
| `EnhancedNotificationDisplay.updateNotificationAsRead` / `clearNotificationForRoom` | Inline reply / mark-read action button — user explicitly engaged with the notification from the shade. |
| `FCMService.handleDismissNotification` (all three cancel branches) | Backend pushed a dismiss because the conversation was marked read server-side (e.g. the user read it in another client, or the read-receipt for an in-app read round-tripped). |
| `MainActivity.onCreate` / `onNewIntent` on the `fromNotification == true && roomId != null` branch | User tapped the notification body. Closes the window between tap and the eventual backend-dismiss FCM, and covers "tap but immediately switch away" where the read-receipt never gets sent. |

**Do NOT clear when:**
- A bubble is open for the room — the bubble's UI continuity depends on the
  cached MessagingStyle, and the dismiss FCM's bubble-open branch already
  skips the system cancel for the same reason.
- The dismiss arrives within the 5-second reply-protection window — that
  dismiss is the backend's echo of our own reply's mark-read; the cache was
  already cleared by the reply path.

The single public entry point is `EnhancedNotificationDisplay.clearRoomMessageCache(roomId)`.

### Notification body formatting

FCM push payloads carry only the plain-text `body` field — the `sanitized_html` / `htmlBody` field is not present. `showEnhancedNotification` therefore cannot call `htmlToNotificationText`. Instead it calls `formatNotificationBody(text)`, a private single-pass parser defined in `EnhancedNotificationDisplay`, which returns a `SpannableStringBuilder` with Android text spans applied:

| Markdown syntax | Span applied |
|---|---|
| `` `code` `` | `TypefaceSpan("monospace")` |
| `**bold**` | `StyleSpan(BOLD)` |
| `*italic*` | `StyleSpan(ITALIC)` |

The parser walks the string left-to-right, checking in the order above (code → bold → italic) so that `**` is consumed before a single `*` is considered. Unmatched delimiters (no closing counterpart found) are emitted verbatim. Underscore-italic (`_..._`) is intentionally omitted to avoid false positives inside Matrix user IDs and URLs.

The resulting `CharSequence` is passed directly as the `MessagingStyle.Message` text, which Android renders with the spans intact in the notification shade on API 24+.

### Image notifications

Image messages (FCM payload contains an `image` field) are handled by the **same single Phase-2 worker** as deferred avatars — see [NotificationImageWorker — the single Phase-2 media worker](#notificationimageworker--the-single-phase-2-media-worker-image--avatars) above. In Phase 1 the notification posts immediately with the caption / body text and **no image download is attempted**; the image URL and MXC cache key are parsed (`deferredHttpUrl` / `deferredMxcUrl`) and handed to `NotificationImageWorker.enqueue()` alongside any missed avatars.

Two details specific to the worker's media handling:

**Expedited scheduling:** the worker is marked **expedited** (`setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`), so `JobScheduler` runs it at the next available slot rather than waiting for a Doze maintenance window — the critical difference from a plain constrained job, which aggressive OEMs (Samsung One UI, MIUI) can defer for minutes or hours. If expedited quota is exhausted it falls back to a normal-priority job.

**Auth token:** `doWork()` re-reads the token from SharedPreferences (`AndromuksAppPrefs / image_auth_token`, falling back to `gomuks_auth_token`) at run time, so a delayed job uses a current credential. It's used as a `Cookie: gomuks_auth=<token>` header by `IntelligentMediaCache.downloadAndCache`; however the image URL already contains `?image_auth=<batchToken>` (appended in `FCMService.handleMessageNotification`) which is the primary credential, and the avatar URLs get `?image_auth=` appended from the same batch token. The cookie is a fallback.

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
